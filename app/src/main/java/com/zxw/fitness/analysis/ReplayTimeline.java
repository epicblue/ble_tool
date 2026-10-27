package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;

import java.util.List;

/**
 * 回放时间轴：把录制的关键点重新喂给判定器，预先算出每一帧对应的计数/计时与提示，
 * 回放拖动进度条时可以直接取值，无需重新计算。
 */
public class ReplayTimeline {

    private final long[] times;
    private final float[] score;
    private final float[] progress;
    private final String[] status;
    private final int[] goodReps;
    private final int[] badReps;
    private final long[] holdMs;
    private final long durationMs;

    private ReplayTimeline(long[] times, float[] score, float[] progress, String[] status,
                          int[] goodReps, int[] badReps, long[] holdMs, long durationMs) {
        this.times = times;
        this.score = score;
        this.progress = progress;
        this.status = status;
        this.goodReps = goodReps;
        this.badReps = badReps;
        this.holdMs = holdMs;
        this.durationMs = durationMs;
    }

    public static ReplayTimeline build(List<PoseFrame> frames, ExerciseType type, String variant) {
        int size = frames.size();
        long[] times = new long[size];
        float[] score = new float[size];
        float[] progress = new float[size];
        String[] status = new String[size];
        int[] good = new int[size];
        int[] bad = new int[size];
        long[] hold = new long[size];

        PushUpCounter pushUp = null;
        HandstandTimer timer = null;
        if (type.isTimerExercise()) {
            timer = new HandstandTimer(type, null);
        } else {
            pushUp = new PushUpCounter(type, variant, null);
        }

        long duration = 0;
        for (int i = 0; i < size; i++) {
            PoseFrame frame = frames.get(i);
            times[i] = frame.getTimestampMs();
            duration = Math.max(duration, times[i]);
            if (pushUp != null) {
                pushUp.process(frame);
                score[i] = type == ExerciseType.BAD_PUSHUP ? pushUp.getBadReps() : pushUp.getGoodReps();
                progress[i] = pushUp.getDepthProgress();
                good[i] = pushUp.getGoodReps();
                bad[i] = pushUp.getBadReps();
                hold[i] = 0;
            } else {
                timer.process(frame);
                score[i] = timer.getBestHoldMs() / 1000f;
                progress[i] = Math.min(1f, timer.getCurrentHoldMs() / 60000f);
                good[i] = 0;
                bad[i] = 0;
                hold[i] = timer.getBestHoldMs();
            }
            status[i] = pushUp != null ? pushUp.getStatusText() : timer.getStatusText();
        }
        return new ReplayTimeline(times, score, progress, status, good, bad, hold, duration);
    }

    public int size() {
        return times.length;
    }

    public long getDurationMs() {
        return durationMs;
    }

    /** 找到时间戳不晚于 timeMs 的最后一帧。 */
    public int indexAt(long timeMs) {
        if (times.length == 0) {
            return -1;
        }
        int low = 0;
        int high = times.length - 1;
        int result = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (times[mid] <= timeMs) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    public float getScoreAt(int index) {
        return index >= 0 && index < score.length ? score[index] : 0f;
    }

    public float getProgressAt(int index) {
        return index >= 0 && index < progress.length ? progress[index] : 0f;
    }

    public String getStatusAt(int index) {
        return index >= 0 && index < status.length ? status[index] : "";
    }

    public int getGoodRepsAt(int index) {
        return index >= 0 && index < goodReps.length ? goodReps[index] : 0;
    }

    public int getBadRepsAt(int index) {
        return index >= 0 && index < badReps.length ? badReps[index] : 0;
    }

    public long getHoldMsAt(int index) {
        return index >= 0 && index < holdMs.length ? holdMs[index] : 0;
    }
}
