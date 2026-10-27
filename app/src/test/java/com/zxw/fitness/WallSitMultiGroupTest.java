package com.zxw.fitness;

import com.zxw.fitness.analysis.FrameGapGuard;
import com.zxw.fitness.analysis.HoldScore;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.WallSitTimer;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.zxw.fitness.SyntheticPoses.FPS_STEP_MS;
import static com.zxw.fitness.SyntheticPoses.wallSit;
import static org.junit.Assert.*;

/** 靠墙蹲实时累计、重放口径和旧记录语义回归。 */
public class WallSitMultiGroupTest {
    @Test public void shorterSecondGroupAccumulatesAndReplayMatchesEveryFrame() {
        WallSitTimer timer = new WallSitTimer(null);
        FrameGapGuard guard = new FrameGapGuard();
        List<PoseFrame> frames = new ArrayList<>();
        List<Long> expected = new ArrayList<>();
        long ts = 0;
        for (int i = 0; i < 50; i++, ts += FPS_STEP_MS) {
            feed(guard, timer, frames, expected, wallSit(ts, 0f));
        }
        long first = timer.getTotalHoldMs();
        assertTrue(first > 3000);
        for (int i = 0; i < 16; i++, ts += FPS_STEP_MS) {
            feed(guard, timer, frames, expected, wallSit(ts, .25f));
            assertEquals(first, timer.getTotalHoldMs());
        }
        assertEquals(0, timer.getCurrentHoldMs());
        assertEquals(first, timer.getBestHoldMs());

        for (int i = 0; i < 20; i++, ts += FPS_STEP_MS) {
            feed(guard, timer, frames, expected, wallSit(ts, 0f));
        }
        long second = timer.getCurrentHoldMs();
        assertEquals(2, timer.getAttempts());
        assertTrue(second > 0 && second < first);
        assertEquals(first, timer.getBestHoldMs());
        assertEquals(first + second, timer.getTotalHoldMs());
        assertEquals(first + second, HoldScore.displayMs(ExerciseType.WALL_SIT, timer));
        assertEquals(first, HoldScore.displayMs(ExerciseType.PLANK, timer));
        assertTrue(expected.get(expected.size() - 1) > expected.get(expected.size() - 5));
        assertTrue(timer.getDetailText().contains("本组"));
        assertTrue(timer.getDetailText().contains("累计"));
        assertTrue(timer.getDetailText().contains("最长单组"));

        long beforeGap = timer.getTotalHoldMs();
        feed(guard, timer, frames, expected, wallSit(ts, .25f)); ts += FPS_STEP_MS;
        feed(guard, timer, frames, expected, wallSit(ts, 0f)); ts += FPS_STEP_MS;
        assertEquals(beforeGap, timer.getTotalHoldMs()); // 无效区间不计入
        feed(guard, timer, frames, expected, wallSit(ts, 0f)); ts += FPS_STEP_MS;
        assertEquals(beforeGap + FPS_STEP_MS, timer.getTotalHoldMs());
        for (int i = 0; i < 16; i++, ts += FPS_STEP_MS) {
            feed(guard, timer, frames, expected, wallSit(ts, .25f));
        }
        assertEquals(0, timer.getCurrentHoldMs());
        assertEquals(beforeGap + FPS_STEP_MS, timer.getTotalHoldMs()); // 不重复累加
        assertEquals(first, timer.getBestHoldMs());

        ReplayTimeline replay = ReplayTimeline.build(frames, ExerciseType.WALL_SIT, "");
        assertEquals(expected.size(), replay.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals("frame " + i, expected.get(i).longValue(), replay.getHoldMsAt(i));
            assertEquals(expected.get(i) / 1000f, replay.getScoreAt(i), .001f);
        }
    }

    private static void feed(FrameGapGuard guard, WallSitTimer timer, List<PoseFrame> frames,
                             List<Long> expected, PoseFrame frame) {
        guard.process(timer, frame);
        frames.add(frame);
        expected.add(HoldScore.displayMs(ExerciseType.WALL_SIT, timer));
    }

    @Test public void longCameraGapDoesNotAddTimeToTheNextGroup() {
        WallSitTimer timer = new WallSitTimer(null);
        FrameGapGuard guard = new FrameGapGuard();
        long ts = 0;
        for (int i = 0; i < 50; i++, ts += FPS_STEP_MS) {
            guard.process(timer, wallSit(ts, 0f));
        }
        long first = timer.getTotalHoldMs();
        ts += 2000;
        guard.process(timer, wallSit(ts, 0f));
        assertEquals(first, timer.getTotalHoldMs());
        assertEquals(0, timer.getCurrentHoldMs());
        for (int i = 0; i < 8; i++) {
            ts += FPS_STEP_MS;
            guard.process(timer, wallSit(ts, 0f));
        }
        assertEquals(2, timer.getAttempts());
        assertTrue(timer.getCurrentHoldMs() > 0);
        assertEquals(first + timer.getCurrentHoldMs(), timer.getTotalHoldMs());
        assertTrue(timer.getTotalHoldMs() <= first + 8 * FPS_STEP_MS);
    }

    @Test public void armingFrameStartsGraceForBadOrMissingNextFrame() {
        for (boolean missing : new boolean[]{false, true}) {
            WallSitTimer timer = new WallSitTimer(null);
            for (long ts = 0; ts <= 400; ts += 100) timer.process(wallSit(ts, 0f));
            timer.process(missing ? PoseFrame.empty(500) : wallSit(500, .25f));
            assertEquals(1, timer.getAttempts());
            assertEquals(400, timer.getCurrentHoldMs());
            timer.process(wallSit(600, 0f));
            assertEquals(400, timer.getCurrentHoldMs());
            timer.process(wallSit(700, 0f));
            assertEquals(500, timer.getCurrentHoldMs());
            assertEquals(500, timer.getTotalHoldMs());
            assertEquals(1, timer.getAttempts());
        }
    }

    @Test public void oldRecordNeverTreatsBestAsTotal() {
        Session old = new Session();
        old.setExerciseType(ExerciseType.WALL_SIT);
        old.setHoldMs(2300);
        assertFalse(old.hasTotalHoldMs());
        assertEquals(-1, old.getTotalHoldMs());
        assertTrue(old.getResultSummary().contains("最长单组 2.3 秒"));
        assertTrue(old.getResultSummary().contains("累计未记录"));
        old.setTotalHoldMs(4600);
        assertEquals("累计 4.6 秒 · 最长单组 2.3 秒", old.getResultSummary());
        assertEquals(2.3, old.getResultValue(), .001); // 历史最佳统计口径不变
    }
}
