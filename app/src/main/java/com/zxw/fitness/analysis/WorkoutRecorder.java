package com.zxw.fitness.analysis;

import android.content.Context;
import android.os.SystemClock;

import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.data.RecordedTimeline.Writer;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.pose.PoseSmoother;

import java.io.File;
import java.io.IOException;

/**
 * 训练过程的记录器：把每帧关键点写入文件，并汇总动作判定结果。
 *
 * <p>职责：</p>
 * <ol>
 *     <li>用 {@link PoseSmoother} 对相机帧做滤波（抗抖动、短暂丢失保持、跳变剔除），
 *     再把结果交给具体判定器；</li>
 *     <li>把滤波帧和判定快照分别写入文件，同版回放可直接复用；</li>
 *     <li>训练结束时把统计结果填进 {@link Session}。</li>
 * </ol>
 */
public class WorkoutRecorder implements ExerciseAnalyzer, RepListener, HoldListener {

    public interface Listener {
        void onRep(RepEvent event);

        void onHoldStart(int attempt);

        void onHoldEnd(int attempt, long durationMs);
    }

    /** 超过这个时间没有新帧时，不能用旧姿势做校准。 */
    private static final long MAX_CALIBRATION_AGE_MS = 750;

    private final Context context;
    private final Session session;
    private final Listener listener;
    private final boolean recordPose;
    private final ExerciseAnalyzer delegate;
    private final RepAnalyzer repAnalyzer;
    private final HoldAnalyzer holdAnalyzer;
    private final PoseSmoother smoother;
    private final FrameGapGuard gapGuard = new FrameGapGuard();

    private PoseLogWriter writer;
    private Writer timelineWriter;
    private File timelineFile;
    private PoseFrame lastRawFrame;
    private long lastFrameReceivedAt = -1;
    private boolean lastFrameRecorded;
    /** 少了任何判定帧都无法保证回放与实时计数一致。 */
    private boolean poseLogComplete = true;
    private int frameCount;
    private boolean finished;

    public WorkoutRecorder(Context context, Session session, boolean recordPose, Listener listener) {
        this.context = context.getApplicationContext();
        this.session = session;
        this.recordPose = recordPose;
        this.listener = listener;
        session.setAlgorithmVersion(AlgorithmVersion.CURRENT);
        session.setTimelineFile(null);
        ExerciseType type = session.getExerciseType();
        smoother = new PoseSmoother();
        if (type.isTimerExercise()) {
            HoldAnalyzer analyzer = createHoldAnalyzer(type);
            holdAnalyzer = analyzer;
            repAnalyzer = null;
            delegate = analyzer;
        } else {
            RepAnalyzer analyzer = createRepAnalyzer(type, session.getVariant());
            repAnalyzer = analyzer;
            holdAnalyzer = null;
            delegate = analyzer;
        }
        if (delegate instanceof PersonalCalibrationAnalyzer) {
            ((PersonalCalibrationAnalyzer) delegate).setPersonalCalibration(session.getInitialCalibration());
        }
    }

    /** 按动作类型创建判定器。新增动作时只需要改这里 + {@link ExerciseType}。 */
    private HoldAnalyzer createHoldAnalyzer(ExerciseType type) {
        if (type == ExerciseType.PLANK) {
            return new PlankTimer(this);
        }
        if (type == ExerciseType.WALL_SIT) {
            return new WallSitTimer(this);
        }
        return new HandstandTimer(type, this);
    }

    private RepAnalyzer createRepAnalyzer(ExerciseType type, String variant) {
        // 这里用 if-else 而不是 switch(R.id)：保持与项目里 AGP 8 的写法习惯一致
        if (type == ExerciseType.SQUAT) {
            return new SquatCounter(this);
        }
        if (type == ExerciseType.PULLUP) {
            return new PullUpCounter(this);
        }
        if (type == ExerciseType.RUNNING) {
            return new RunStepCounter(this);
        }
        return new PushUpCounter(type, variant, this);
    }

    /**
     * 处理一帧（实时与回放共用）。
     *
     * @param frame        原始关键点
     * @param sourceWidth  摆正后画面宽（用于骨架文件）
     * @param sourceHeight 摆正后画面高
     * @param rollDegrees 本帧用于重力对齐的旋转角
     */
    public void onPose(PoseFrame frame, int sourceWidth, int sourceHeight, float rollDegrees) {
        if (finished || frame == null) {
            return;
        }
        lastRawFrame = frame;
        lastFrameReceivedAt = SystemClock.elapsedRealtime();
        PoseFrame filtered = smoother.smooth(frame);
        lastFrameRecorded = false;
        if (recordPose) {
            int before = frameCount;
            recordFrame(filtered, sourceWidth, sourceHeight, rollDegrees);
            lastFrameRecorded = frameCount > before;
            if (!lastFrameRecorded) poseLogComplete = false;
        }
        gapGuard.process(delegate, filtered);
        if (lastFrameRecorded && timelineWriter != null) {
            timelineWriter.addFrame(filtered, ReplayTimeline.capture(session.getExerciseType(),
                    repAnalyzer, holdAnalyzer));
        }
    }

    /**
     * 只做判定，不写骨架文件（回放时使用，数据已经是滤波后的）。
     */
    public void processRecorded(PoseFrame frame) {
        gapGuard.process(delegate, frame);
    }

    private void recordFrame(PoseFrame frame, int width, int height, float rollDegrees) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (writer == null) {
            File file = SessionStore.resolveMedia(context, session.getUserId(),
                    SessionStore.mediaPrefix(session.getId()) + "_pose.dat");
            try {
                writer = new PoseLogWriter(file, width, height);
                session.setPoseFile(file.getName());
                timelineFile = SessionStore.resolveMedia(context, session.getUserId(),
                        SessionStore.mediaPrefix(session.getId()) + "_timeline.dat");
                try {
                    timelineWriter = new Writer(timelineFile, session);
                } catch (IOException | RuntimeException error) {
                    // 即使逐帧结果缓存不可写，原有骨架与实时计数仍继续工作。
                    timelineWriter = null;
                    if (timelineFile != null) timelineFile.delete();
                }
            } catch (IOException e) {
                writer = null;
                if (file != null) file.delete();
                return;
            }
        }
        // 每一帧既参与实时判定也参与回放；校准事件记录帧下标，避免漏帧导致次数变化。
        int before = writer.getFrameCount();
        writer.addFrame(frame, rollDegrees);
        if (writer.getFrameCount() > before) frameCount++;
    }

    /**
     * 确认当前帧并立即记一次。失败时返回可显示的原因，不更改设置或成绩。
     * 主线程上串行处理相机回调与点击，使事件精确落在最近一帧之后。
     */
    public String calibratePersonal() {
        if (finished || !(delegate instanceof PersonalCalibrationAnalyzer)) {
            return "该动作暂不支持个人幅度校准";
        }
        if (lastRawFrame == null || lastFrameReceivedAt < 0
                || SystemClock.elapsedRealtime() - lastFrameReceivedAt > MAX_CALIBRATION_AGE_MS) {
            return "没有新的姿态画面，请确认相机正在识别";
        }
        if (recordPose && (!lastFrameRecorded || !poseLogComplete)) {
            return "骨架录制不完整，无法保存可回放的校准；请检查存储后开始新一组";
        }
        PersonalCalibrationAnalyzer analyzer = (PersonalCalibrationAnalyzer) delegate;
        PersonalCalibration calibration = analyzer.capturePersonalCalibration(lastRawFrame);
        if (calibration == null) return analyzer.getCalibrationError();
        if (!calibration.isValid() || !calibration.matches(session.getExerciseType(), session.getVariant())) {
            return "识别到的姿态参数不可靠，请调整后重试";
        }
        if (recordPose && (writer == null || !writer.flush())) {
            return "骨架记录写入失败，本次未校准；请检查存储空间";
        }
        if (!UserStore.saveCalibration(context, session.getUserId(), calibration)) {
            return "个人设置保存失败，请检查存储后重试";
        }
        if (recordPose && frameCount > 0) {
            session.addCalibrationChange(frameCount - 1, calibration);
        }
        analyzer.applyCalibrationEvent(calibration);
        if (recordPose && timelineWriter != null && frameCount > 0) {
            timelineWriter.addCalibration(ReplayTimeline.capture(session.getExerciseType(),
                    repAnalyzer, holdAnalyzer));
        }
        return null;
    }

    /** 本帧原始关键点中，滤波器因大幅跳变而暂未采纳的点。 */
    public boolean[] getPendingJumps() { return smoother.getPendingJumps(); }

    public int getFrameCount() {
        return frameCount;
    }

    public boolean hasRecordedPose() {
        return writer != null && frameCount > 0 && poseLogComplete && !writer.hasError();
    }

    /**
     * 结束记录，把统计结果写入 {@link Session}（不落盘，由调用方决定何时保存）。
     */
    public Session finish(long endTimestampMs) {
        if (finished) {
            return session;
        }
        finished = true;
        if (writer != null) {
            writer.close();
        }
        long duration = Math.max(0, endTimestampMs - session.getStartedAt());
        session.setDurationMs(duration);
        session.setStability(holdAnalyzer != null ? holdAnalyzer.getStability() : Float.NaN);
        if (repAnalyzer != null) {
            session.setReps(repAnalyzer.getGoodReps());
            session.setBadReps(repAnalyzer.getBadReps());
            session.setFormErrors(repAnalyzer.getFormErrors());
        } else if (holdAnalyzer != null) {
            // holdMs 仍表示最长单组，兼容旧记录和历史最佳统计。
            session.setHoldMs(holdAnalyzer.getBestHoldMs());
            session.setTotalHoldMs(holdAnalyzer.getTotalHoldMs());
            session.setFormErrors(0);
            if (session.getExerciseType() == ExerciseType.WALL_HANDSTAND) {
                float wallRatio = holdAnalyzer instanceof HandstandTimer
                        ? ((HandstandTimer) holdAnalyzer).getWallRatio() : 0f;
                session.setWallContact(wallRatio >= 0.5f);
            }
        }
        if (timelineWriter != null) {
            File pose = SessionStore.resolveMedia(context, session.getUserId(), session.getPoseFile());
            if (hasRecordedPose() && timelineWriter.finish(pose, session, frameCount)) {
                session.setTimelineFile(timelineFile.getName());
            } else {
                timelineWriter.discard();
                session.setTimelineFile(null);
            }
            timelineWriter = null;
        }
        if (!hasRecordedPose()) {
            File failedPose = SessionStore.resolveMedia(context, session.getUserId(), session.getPoseFile());
            if (failedPose != null) {
                //noinspection ResultOfMethodCallIgnored
                failedPose.delete();
            }
            session.setPoseFile(null);
        }
        return session;
    }

    public boolean isFinished() {
        return finished;
    }

    /** 计数类判定器（计时类动作返回 null）。 */
    public RepAnalyzer getRepAnalyzer() {
        return repAnalyzer;
    }

    /** 计时类判定器（计数类动作返回 null）。 */
    public HoldAnalyzer getHoldAnalyzer() {
        return holdAnalyzer;
    }

    /** 兼容旧调用：俯卧撑类动作返回具体实现，否则 null。 */
    public PushUpCounter getPushUpCounter() {
        return repAnalyzer instanceof PushUpCounter ? (PushUpCounter) repAnalyzer : null;
    }

    /** 兼容旧调用：倒立类动作返回具体实现，否则 null。 */
    public HandstandTimer getHandstandTimer() {
        return holdAnalyzer instanceof HandstandTimer ? (HandstandTimer) holdAnalyzer : null;
    }

    public PoseSmoother getSmoother() {
        return smoother;
    }

    // ---- 透传 ----

    @Override
    public void reset() {
        smoother.reset();
        gapGuard.reset();
        delegate.reset();
        lastRawFrame = null;
        lastFrameReceivedAt = -1;
        lastFrameRecorded = false;
    }

    @Override
    public void process(PoseFrame frame) {
        gapGuard.process(delegate, frame);
    }

    @Override
    public String getScoreText() {
        return delegate.getScoreText();
    }

    @Override
    public String getStatusText() {
        return delegate.getStatusText();
    }

    @Override
    public String getDetailText() {
        return delegate.getDetailText();
    }

    @Override
    public boolean hasResult() {
        return delegate.hasResult();
    }

    // ---- 判定器回调 ----

    @Override
    public void onRep(RepEvent event) {
        if (listener != null) {
            listener.onRep(event);
        }
    }

    @Override
    public void onHoldStart(int attemptIndex) {
        if (listener != null) {
            listener.onHoldStart(attemptIndex);
        }
    }

    @Override
    public void onHoldEnd(int attemptIndex, long durationMs) {
        if (listener != null) {
            listener.onHoldEnd(attemptIndex, durationMs);
        }
    }
}
