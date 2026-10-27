package com.zxw.fitness.analysis;

import android.content.Context;

import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
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
 *     <li>把滤波后的关键点按固定间隔写入骨架文件，回放时重放同一套数据；</li>
 *     <li>训练结束时把统计结果填进 {@link Session}。</li>
 * </ol>
 */
public class WorkoutRecorder implements ExerciseAnalyzer, RepListener, HoldListener {

    public interface Listener {
        void onRep(RepEvent event);

        void onHoldStart(int attempt);

        void onHoldEnd(int attempt, long durationMs);
    }

    /** 骨架录制的最小间隔（毫秒），约 20fps，兼顾流畅与体积。 */
    private static final long MIN_RECORD_INTERVAL_MS = 45;

    private final Context context;
    private final Session session;
    private final Listener listener;
    private final boolean recordPose;
    private final ExerciseAnalyzer delegate;
    private final RepAnalyzer repAnalyzer;
    private final HoldAnalyzer holdAnalyzer;
    private final PoseSmoother smoother = new PoseSmoother();

    private PoseLogWriter writer;
    private long lastRecordedMs = -1;
    private int frameCount;
    private boolean finished;

    public WorkoutRecorder(Context context, Session session, boolean recordPose, Listener listener) {
        this.context = context.getApplicationContext();
        this.session = session;
        this.recordPose = recordPose;
        this.listener = listener;
        ExerciseType type = session.getExerciseType();
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
    }

    /** 按动作类型创建判定器。新增动作时只需要改这里 + {@link ExerciseType}。 */
    private HoldAnalyzer createHoldAnalyzer(ExerciseType type) {
        if (type == ExerciseType.PLANK) {
            return new PlankTimer(this);
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
        return new PushUpCounter(type, variant, this);
    }

    /**
     * 处理一帧（实时与回放共用）。
     *
     * @param frame        原始关键点
     * @param sourceWidth  摆正后画面宽（用于骨架文件）
     * @param sourceHeight 摆正后画面高
     */
    public void onPose(PoseFrame frame, int sourceWidth, int sourceHeight) {
        if (finished || frame == null) {
            return;
        }
        PoseFrame filtered = smoother.smooth(frame);
        if (recordPose) {
            recordFrame(filtered, sourceWidth, sourceHeight);
        }
        delegate.process(filtered);
    }

    /**
     * 只做判定，不写骨架文件（回放时使用，数据已经是滤波后的）。
     */
    public void processRecorded(PoseFrame frame) {
        delegate.process(frame);
    }

    private void recordFrame(PoseFrame frame, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (writer == null) {
            File file = SessionStore.resolveMedia(context, session.getUserId(),
                    SessionStore.mediaPrefix(session.getId()) + "_pose.dat");
            try {
                writer = new PoseLogWriter(file, width, height);
                session.setPoseFile(file.getName());
            } catch (IOException e) {
                writer = null;
                return;
            }
        }
        if (lastRecordedMs >= 0 && frame.getTimestampMs() - lastRecordedMs < MIN_RECORD_INTERVAL_MS) {
            return;
        }
        lastRecordedMs = frame.getTimestampMs();
        writer.addFrame(frame);
        frameCount++;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public boolean hasRecordedPose() {
        return writer != null && frameCount > 0;
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
            session.setHoldMs(holdAnalyzer.getBestHoldMs());
            session.setFormErrors(0);
            if (session.getExerciseType() == ExerciseType.WALL_HANDSTAND) {
                float wallRatio = holdAnalyzer instanceof HandstandTimer
                        ? ((HandstandTimer) holdAnalyzer).getWallRatio() : 0f;
                session.setWallContact(wallRatio >= 0.5f);
            }
        }
        if (!hasRecordedPose()) {
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
        delegate.reset();
    }

    @Override
    public void process(PoseFrame frame) {
        delegate.process(frame);
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
