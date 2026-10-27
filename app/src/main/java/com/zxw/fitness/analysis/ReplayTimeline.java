package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.CalibrationChange;
import com.zxw.fitness.model.PersonalCalibration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 回放时间轴：把录制或视频重识别后的关键点喂给判定器，预先算出每一帧对应的计数/计时与提示，
 * 回放拖动进度条时可以直接取值，无需重新计算。
 *
 * <p>与实时训练共用同一套判定器，算法升级后旧记录的回放成绩可能不同。</p>
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
        return build(frames, type, variant, null, Collections.emptyList());
    }

    /** 老记录没有校准数据时仍按默认标准回放。 */
    public static ReplayTimeline build(List<PoseFrame> frames, Session session) {
        return build(frames, session.getExerciseType(), session.getVariant(),
                session.getInitialCalibration(), session.getCalibrationChanges());
    }

    /**
     * 从视频重新抽样时只使用训练开始时的个人目标。
     * 录制中的校准事件以旧骨架帧下标保存，且旧骨架和录像的起点并未同步记录；
     * 不能把这些下标直接用于新的采样，也不能假装其时间精确对应视频。
     */
    public static ReplayTimeline buildForVideo(List<PoseFrame> frames, Session session) {
        return build(frames, session.getExerciseType(), session.getVariant(),
                session.getInitialCalibration(), Collections.emptyList());
    }

    private static ReplayTimeline build(List<PoseFrame> frames, ExerciseType type, String variant,
                                        PersonalCalibration initial,
                                        List<CalibrationChange> changes) {
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
            if (type == ExerciseType.PLANK) {
                holdAnalyzer = new PlankTimer(null);
            } else if (type == ExerciseType.WALL_SIT) {
                holdAnalyzer = new WallSitTimer(null);
            } else {
                holdAnalyzer = new HandstandTimer(type, null);
            }
        } else if (type == ExerciseType.SQUAT) {
            repAnalyzer = new SquatCounter(null);
        } else if (type == ExerciseType.PULLUP) {
            repAnalyzer = new PullUpCounter(null);
        } else if (type == ExerciseType.RUNNING) {
            repAnalyzer = new RunStepCounter(null);
        } else {
            repAnalyzer = new PushUpCounter(type, variant, null);
        }

        PersonalCalibrationAnalyzer calibrated = repAnalyzer instanceof PersonalCalibrationAnalyzer
                ? (PersonalCalibrationAnalyzer) repAnalyzer : null;
        if (calibrated != null) calibrated.setPersonalCalibration(initial);
        List<CalibrationChange> events = new ArrayList<>(changes);
        Collections.sort(events, new Comparator<CalibrationChange>() {
            @Override public int compare(CalibrationChange a, CalibrationChange b) {
                return Integer.compare(a.frameIndex, b.frameIndex);
            }
        });
        int nextEvent = 0;
        FrameGapGuard gapGuard = new FrameGapGuard();
        long duration = 0;
        for (int i = 0; i < size; i++) {
            PoseFrame frame = frames.get(i);
            times[i] = frame.getTimestampMs();
            duration = Math.max(duration, times[i]);
            if (repAnalyzer != null) {
                gapGuard.process(repAnalyzer, frame);
                while (nextEvent < events.size() && events.get(nextEvent).frameIndex <= i) {
                    if (calibrated != null) {
                        calibrated.applyCalibrationEvent(events.get(nextEvent).calibration);
                    }
                    nextEvent++;
                }
                int goodNow = repAnalyzer.getGoodReps();
                int badNow = repAnalyzer.getBadReps();
                score[i] = type == ExerciseType.BAD_PUSHUP ? badNow : goodNow;
                progress[i] = repAnalyzer.getDepthProgress();
                good[i] = goodNow;
                bad[i] = badNow;
                hold[i] = 0;
                status[i] = repAnalyzer.getStatusText();
            } else {
                gapGuard.process(holdAnalyzer, frame);
                long displayedHoldMs = HoldScore.displayMs(type, holdAnalyzer);
                score[i] = displayedHoldMs / 1000f;
                progress[i] = Math.min(1f, holdAnalyzer.getCurrentHoldMs() / 60000f);
                good[i] = 0;
                bad[i] = 0;
                hold[i] = displayedHoldMs;
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

    /** 逐帧展示值：靠墙蹲是累计有效时长，其余计时动作是最长单组。 */
    public long getHoldMsAt(int index) {
        return index >= 0 && index < holdMs.length ? holdMs[index] : 0;
    }
}
