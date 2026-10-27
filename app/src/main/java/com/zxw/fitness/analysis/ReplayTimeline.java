package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;

import java.util.List;

/**
 * 回放时间轴：把录制的关键点重新喂给判定器，预先算出每一帧对应的计数/计时与提示，
 * 回放拖动进度条时可以直接取值，无需重新计算。
 *
 * <p>与实时训练共用同一套判定器，因此回放显示的成绩与录制时一致。</p>
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

        RepAnalyzer repAnalyzer = null;
        HoldAnalyzer holdAnalyzer = null;
        if (type.isTimerExercise()) {
            holdAnalyzer = type == ExerciseType.PLANK
                    ? new PlankTimer(null)
                    : new HandstandTimer(type, null);
        } else if (type == ExerciseType.SQUAT) {
            repAnalyzer = new SquatCounter(null);
        } else if (type == ExerciseType.PULLUP) {
            repAnalyzer = new PullUpCounter(null);
        } else {
            repAnalyzer = new PushUpCounter(type, variant, null);
        }

        long duration = 0;
        for (int i = 0; i < size; i++) {
            PoseFrame frame = frames.get(i);
            times[i] = frame.getTimestampMs();
            duration = Math.max(duration, times[i]);
            if (repAnalyzer != null) {
                repAnalyzer.process(frame);
                int goodNow = repAnalyzer.getGoodReps();
                int badNow = repAnalyzer.getBadReps();
                score[i] = type == ExerciseType.BAD_PUSHUP ? badNow : goodNow;
                progress[i] = repAnalyzer.getDepthProgress();
                good[i] = goodNow;
                bad[i] = badNow;
                hold[i] = 0;
                status[i] = repAnalyzer.getStatusText();
            } else {
                holdAnalyzer.process(frame);
                score[i] = holdAnalyzer.getBestHoldMs() / 1000f;
                progress[i] = Math.min(1f, holdAnalyzer.getCurrentHoldMs() / 60000f);
                good[i] = 0;
                bad[i] = 0;
                hold[i] = holdAnalyzer.getBestHoldMs();
                status[i] = holdAnalyzer.getStatusText();
            }
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
