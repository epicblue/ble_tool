package com.zxw.fitness.analysis;

import android.content.Context;

import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;

import java.io.File;
import java.io.IOException;

/**
 * 训练过程的记录器：把每帧关键点写入文件，并汇总动作判定结果。
 *
 * <p>对外的界面部分（计数/计时/提示）直接透传给具体判定器。</p>
 */
public class WorkoutRecorder implements ExerciseAnalyzer,
        PushUpCounter.Listener, HandstandTimer.Listener {

    public interface Listener {
        void onRep(PushUpCounter.RepEvent event);

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
    private final PushUpCounter pushUpCounter;
    private final HandstandTimer handstandTimer;

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
            handstandTimer = new HandstandTimer(type, this);
            pushUpCounter = null;
            delegate = handstandTimer;
        } else {
            pushUpCounter = new PushUpCounter(type, session.getVariant(), this);
            handstandTimer = null;
            delegate = pushUpCounter;
        }
    }

    /** 处理一帧（实时与回放共用）。 */
    public void onPose(PoseFrame frame, int sourceWidth, int sourceHeight) {
        if (finished || frame == null) {
            return;
        }
        if (recordPose) {
            recordFrame(frame, sourceWidth, sourceHeight);
        }
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
        if (pushUpCounter != null) {
            session.setReps(pushUpCounter.getGoodReps());
            session.setBadReps(pushUpCounter.getBadReps());
            session.setFormErrors(pushUpCounter.getFormErrors());
        } else if (handstandTimer != null) {
            session.setHoldMs(handstandTimer.getBestHoldMs());
            session.setWallContact(handstandTimer.getWallRatio() >= 0.5f);
        }
        if (!hasRecordedPose()) {
            session.setPoseFile(null);
        }
        return session;
    }

    public boolean isFinished() {
        return finished;
    }

    public PushUpCounter getPushUpCounter() {
        return pushUpCounter;
    }

    public HandstandTimer getHandstandTimer() {
        return handstandTimer;
    }

    // ---- 透传 ----

    @Override
    public void reset() {
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
    public void onRep(PushUpCounter.RepEvent event) {
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
