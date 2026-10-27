package com.zxw.fitness;

import com.zxw.fitness.analysis.FrameGapGuard;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.SquatCounter;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.pose.PoseMath;
import com.zxw.fitness.pose.PoseSmoother;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.zxw.fitness.SyntheticPoses.FPS_STEP_MS;
import static com.zxw.fitness.SyntheticPoses.lowerConfidence;
import static com.zxw.fitness.SyntheticPoses.shiftLandmarks;
import static com.zxw.fitness.SyntheticPoses.squat;
import static org.junit.Assert.*;

/** 合成横移与蹲起轨迹；不能代替真实相机与姿态模型测试。 */
public class SquatPanRecoveryTest {
    private static PoseFrame translated(long time, float knee, float drop, float dx) {
        return shiftLandmarks(squat(time, knee, drop), dx, 0f, PoseFrame.LANDMARK_SUBSET);
    }

    private static float knee(PoseFrame frame) {
        return PoseMath.averageAngle(frame,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE);
    }

    @Test public void squatRecoversDuringContinuedPanAndReplayKeepsCounts() throws Exception {
        PoseSmoother smoother = new PoseSmoother(true);
        SquatCounter counter = new SquatCounter(null);
        FrameGapGuard guard = new FrameGapGuard();
        List<PoseFrame> recorded = new ArrayList<>();
        List<Integer> expectedGood = new ArrayList<>();
        List<Integer> expectedBad = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 8; i++, t += FPS_STEP_MS) {
            append(smoother, counter, guard, recorded, expectedGood, expectedBad,
                    translated(t, 168f, 0f, -.12f));
        }
        for (int i = 0; i < 9; i++, t += FPS_STEP_MS) {
            append(smoother, counter, guard, recorded, expectedGood, expectedBad,
                    translated(t, 168f - (i + 1) * 8f, (i + 1) * .06f, -.12f));
        }
        PoseFrame lastRaw = null, lastFiltered = null;
        for (int i = 0; i < 32; i++, t += FPS_STEP_MS) {
            float angle = i < 8 ? 96f + i * 9f : 168f;
            float drop = i < 8 ? .54f * (1f - (i + 1) / 8f) : 0f;
            lastRaw = translated(t, angle, drop, .04f + i * .008f);
            lastFiltered = append(smoother, counter, guard, recorded, expectedGood, expectedBad, lastRaw);
        }
        assertNotNull(lastFiltered);
        assertTrue("相机继续横移时应恢复肩、髋、膝、踝的可信轨迹",
                lastFiltered.isReliable(PoseFrame.LEFT_SHOULDER)
                        && lastFiltered.isReliable(PoseFrame.LEFT_HIP)
                        && lastFiltered.isReliable(PoseFrame.LEFT_KNEE)
                        && lastFiltered.isReliable(PoseFrame.LEFT_ANKLE));
        assertTrue("站起后不应继续锁在蹲姿", knee(lastFiltered) > 155f);
        assertEquals(lastRaw.getX(PoseFrame.LEFT_SHOULDER),
                lastFiltered.getX(PoseFrame.LEFT_SHOULDER), .04f);
        assertEquals("丢失期间不能补计未观察到的往返", 0, counter.getGoodReps() + counter.getBadReps());

        float dx = .04f + 31 * .008f;
        for (float angle : new float[]{175, 160, 140, 120, 95, 95, 120, 140, 160, 175}) {
            float drop = angle < 150 ? (150 - angle) / 150f : 0f;
            for (int i = 0; i < 3; i++, t += FPS_STEP_MS) {
                append(smoother, counter, guard, recorded, expectedGood, expectedBad,
                        translated(t, angle, drop, dx));
            }
        }
        assertEquals("恢复后下一次完整深蹲应正常计数", 1, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        ReplayTimeline replay = ReplayTimeline.build(recorded, ExerciseType.SQUAT, "");
        assertEquals(recorded.size(), replay.size());
        for (int i = 0; i < recorded.size(); i++) {
            assertEquals("回放有效次数，第 " + i + " 帧", (int) expectedGood.get(i), replay.getGoodRepsAt(i));
            assertEquals("回放不标准次数，第 " + i + " 帧", (int) expectedBad.get(i), replay.getBadRepsAt(i));
        }
        // 真正经过 v2 二进制骨架文件，再比较每帧回放；视频摄录仍需设备验证。
        File file = File.createTempFile("squat-pan-replay", ".dat");
        try {
            PoseLogWriter writer = new PoseLogWriter(file, 720, 1280);
            for (PoseFrame frame : recorded) writer.addFrame(frame, 12f);
            writer.close();
            assertFalse(writer.hasError());
            PoseLog log = PoseLog.load(file);
            assertNotNull(log);
            assertEquals(recorded.size(), log.getFrames().size());
            assertEquals(recorded.size(), log.getDisplayFrames().size());
            ReplayTimeline fromDisk = ReplayTimeline.build(log.getFrames(), ExerciseType.SQUAT, "");
            for (int i = 0; i < recorded.size(); i++) {
                assertEquals("磁盘回放有效次数，第 " + i + " 帧", (int) expectedGood.get(i), fromDisk.getGoodRepsAt(i));
                assertEquals("磁盘回放不标准次数，第 " + i + " 帧", (int) expectedBad.get(i), fromDisk.getBadRepsAt(i));
            }
        } finally {
            if (!file.delete()) file.deleteOnExit();
        }
    }

    private static PoseFrame append(PoseSmoother smoother, SquatCounter counter, FrameGapGuard guard,
                                    List<PoseFrame> recorded, List<Integer> good, List<Integer> bad,
                                    PoseFrame raw) {
        PoseFrame filtered = smoother.smooth(raw);
        guard.process(counter, filtered);
        recorded.add(filtered);
        good.add(counter.getGoodReps());
        bad.add(counter.getBadReps());
        return filtered;
    }

    @Test public void alternatingWholeBodyJumpsCannotBecomeAPan() {
        PoseSmoother smoother = new PoseSmoother(true);
        smoother.smooth(translated(0, 168f, 0f, 0f));
        PoseFrame first = smoother.smooth(translated(FPS_STEP_MS, 168f, 0f, .18f));
        assertTrue("单帧整体跳变仍必须等待", smoother.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        assertEquals(.45f, first.getX(PoseFrame.LEFT_SHOULDER), .001f);
        PoseFrame last = first;
        for (int n = 2; n < 33; n++) {
            last = smoother.smooth(translated(n * FPS_STEP_MS, 168f, 0f, n % 2 == 0 ? -.18f : .18f));
        }
        assertEquals(0, smoother.getReacquiredJumps());
        assertFalse(last.isReliable(PoseFrame.LEFT_SHOULDER));
    }

    @Test public void missingAnchorRestartsCoherentPanEvidence() {
        PoseSmoother smoother = new PoseSmoother(true);
        smoother.smooth(translated(0, 168f, 0f, 0f));
        for (int n = 1; n <= 5; n++) smoother.smooth(translated(n * FPS_STEP_MS, 168f, 0f, .18f));
        smoother.smooth(lowerConfidence(translated(6 * FPS_STEP_MS, 168f, 0f, .18f),
                .05f, PoseFrame.LEFT_SHOULDER));
        PoseFrame out = null;
        for (int n = 7; n <= 15; n++) out = smoother.smooth(translated(n * FPS_STEP_MS, 168f, 0f, .18f));
        assertNotNull(out);
        assertFalse("缺失后不能提前用整个人体平移绕过确认", out.isReliable(PoseFrame.LEFT_SHOULDER));
        for (int n = 16; n <= 20; n++) out = smoother.smooth(translated(n * FPS_STEP_MS, 168f, 0f, .18f));
        assertTrue(out.isReliable(PoseFrame.LEFT_SHOULDER));
    }

    @Test public void immediateStandingDuringPanCannotCompleteUnseenHalfRep() {
        PoseSmoother smoother = new PoseSmoother(true);
        SquatCounter counter = new SquatCounter(null);
        FrameGapGuard guard = new FrameGapGuard();
        long t = 0;
        for (int i = 0; i < 8; i++, t += FPS_STEP_MS) {
            guard.process(counter, smoother.smooth(lowerConfidence(
                    translated(t, 175f, 0f, -.12f), 1f, PoseFrame.LANDMARK_SUBSET)));
        }
        for (int i = 0; i < 10; i++, t += FPS_STEP_MS) {
            guard.process(counter, smoother.smooth(lowerConfidence(
                    translated(t, 175f - (i + 1) * 8f, (i + 1) * .06f, -.12f),
                    1f, PoseFrame.LANDMARK_SUBSET)));
        }
        for (int i = 0; i < 24; i++, t += FPS_STEP_MS) {
            guard.process(counter, smoother.smooth(lowerConfidence(
                    translated(t, 175f, 0f, .04f + i * .008f),
                    1f, PoseFrame.LANDMARK_SUBSET)));
        }
        assertTrue("横移轨迹应得到重捕获", smoother.getReacquiredJumps() > 0);
        assertEquals("未知区间不能把下蹲前半段与站姿拼成一次", 0,
                counter.getGoodReps() + counter.getBadReps());
    }

    @Test public void highFrequencyPanCannotStitchAnUnseenSquat() {
        assertHighFrequencyPanDoesNotStitch(false);
        assertHighFrequencyPanDoesNotStitch(true);
    }

    private static void assertHighFrequencyPanDoesNotStitch(boolean interrupted) {
        // 极高帧率是边界合成对照：700ms 候选可能早于深蹲状态机的 500ms 丢失门槛。
        // 中间丢点重置横移证据时，逐点候选也不能趁机拼接丢失前后的半次动作。
        final int step = 5;
        PoseSmoother smoother = new PoseSmoother(true);
        SquatCounter counter = new SquatCounter(null);
        FrameGapGuard guard = new FrameGapGuard();
        long t = 0;
        for (int i = 0; i < 80; i++, t += step) {
            guard.process(counter, smoother.smooth(lowerConfidence(
                    translated(t, 175f, 0f, 0f), 1f, PoseFrame.LANDMARK_SUBSET)));
        }
        for (float angle : new float[]{160, 145, 130, 115, 90}) {
            float drop = .6f * (175f - angle) / 85f;
            for (int i = 0; i < 20; i++, t += step) {
                guard.process(counter, smoother.smooth(lowerConfidence(
                        translated(t, angle, drop, 0f), 1f, PoseFrame.LANDMARK_SUBSET)));
            }
        }
        for (int i = 0; i < 180; i++, t += step) {
            PoseFrame raw = lowerConfidence(translated(t, 175f, 0f, .14f + i * .0003f),
                    1f, PoseFrame.LANDMARK_SUBSET);
            if (interrupted && i == 140) {
                raw = lowerConfidence(raw, .05f, PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_ANKLE);
            }
            guard.process(counter, smoother.smooth(raw));
        }
        assertTrue(smoother.getReacquiredJumps() > 0);
        assertEquals("连续横移与逐点候选都不能补记丢失的往返，中断=" + interrupted, 0,
                counter.getGoodReps() + counter.getBadReps());
    }

    @Test public void wholePanDoesNotAdoptASeparateKneeSpike() {
        for (float[] spike : new float[][]{{-.25f, 0f}, {0f, .35f}}) {
            PoseSmoother smoother = new PoseSmoother(true);
            smoother.smooth(translated(0, 168f, 0f, 0f));
            for (int n = 1; n < 12; n++) {
                smoother.smooth(translated(n * FPS_STEP_MS, 168f, 0f, .16f + n * .008f));
            }
            PoseFrame raw = translated(12 * FPS_STEP_MS, 168f, 0f, .16f + 12 * .008f);
            raw = shiftLandmarks(raw, spike[0], spike[1], PoseFrame.LEFT_KNEE, PoseFrame.RIGHT_KNEE);
            PoseFrame out = smoother.smooth(raw);
            assertTrue("肩髋踝可按一致横移重捕获", out.isReliable(PoseFrame.LEFT_SHOULDER));
            assertFalse("横移确认时无关膝点的横向或纵向大跳变仍须等待",
                    out.isReliable(PoseFrame.LEFT_KNEE));
            assertTrue(smoother.getPendingJumps()[PoseFrame.LEFT_KNEE]);
        }
    }

    @Test public void inconsistentAnchorsCannotTriggerWholePan() {
        PoseSmoother smoother = new PoseSmoother(true);
        smoother.smooth(translated(0, 168f, 0f, 0f));
        PoseFrame out = null;
        for (int n = 1; n <= 24; n++) {
            float dx = .16f + n * .008f;
            PoseFrame moved = translated(n * FPS_STEP_MS, 168f, 0f, dx);
            moved = shiftLandmarks(moved, -dx, 0f, PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE);
            out = smoother.smooth(moved);
        }
        assertNotNull(out);
        assertFalse("脚踝未同步位移，不能把躯干误当整体横移", out.isReliable(PoseFrame.LEFT_SHOULDER));
        assertEquals(0, smoother.getReacquiredJumps());
    }

    @Test public void lowConfidenceAnchorsDoNotConfirmCoherentPan() {
        PoseSmoother smoother = new PoseSmoother(true);
        smoother.smooth(translated(0, 168f, 0f, 0f));
        PoseFrame out = null;
        for (int n = 1; n <= 22; n++) {
            PoseFrame raw = translated(n * FPS_STEP_MS, 168f, 0f, .16f + n * .008f);
            raw = lowerConfidence(raw, .55f, PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_HIP,
                    PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP,
                    PoseFrame.RIGHT_ANKLE);
            out = smoother.smooth(raw);
        }
        assertNotNull(out);
        assertFalse("横移证据的肩髋踝低于 0.65 时不能整体放行", out.isReliable(PoseFrame.LEFT_SHOULDER));
        assertEquals(0, smoother.getReacquiredJumps());
    }

    @Test public void defaultSmootherDoesNotEnableSquatSpecificPanRecovery() {
        PoseSmoother regular = new PoseSmoother();
        PoseSmoother squatSmoother = new PoseSmoother(true);
        PoseFrame start = translated(0, 168f, 0f, 0f);
        regular.smooth(start);
        squatSmoother.smooth(start);
        PoseFrame regularOut = null, squatOut = null;
        for (int n = 1; n <= 22; n++) {
            PoseFrame raw = translated(n * FPS_STEP_MS, 168f, 0f, .16f + n * .008f);
            regularOut = regular.smooth(raw);
            squatOut = squatSmoother.smooth(raw);
        }
        assertNotNull(regularOut);
        assertNotNull(squatOut);
        assertFalse("默认策略维持其他动作的逐点候选", regularOut.isReliable(PoseFrame.LEFT_SHOULDER));
        assertTrue("只有显式启用的深蹲策略整体恢复", squatOut.isReliable(PoseFrame.LEFT_SHOULDER));
    }

    @Test public void abortedPanDoesNotLockAnIsolatedJointForever() {
        PoseSmoother smoother = new PoseSmoother(true);
        smoother.smooth(translated(0, 168f, 0f, 0f));
        for (int n = 1; n <= 4; n++) {
            smoother.smooth(translated(n * FPS_STEP_MS, 168f, 0f, .18f));
        }
        // 回到旧位置并保持足够久，可以重新启用正常的逐点候选。
        for (int n = 5; n <= 18; n++) {
            smoother.smooth(translated(n * FPS_STEP_MS, 168f, 0f, 0f));
        }
        PoseFrame out = null;
        for (int n = 19; n <= 33; n++) {
            PoseFrame raw = shiftLandmarks(translated(n * FPS_STEP_MS, 168f, 0f, 0f),
                    .18f, 0f, PoseFrame.LEFT_WRIST);
            out = smoother.smooth(raw);
        }
        assertNotNull(out);
        assertTrue("恢复稳定后单点仍可按原规则重捕获", out.isReliable(PoseFrame.LEFT_WRIST));
        assertFalse(smoother.getPendingJumps()[PoseFrame.LEFT_WRIST]);
    }

    @Test public void slowCandidatesCannotBypassGapSafetyButRecoverWhenFramesSpeedUp() {
        PoseSmoother smoother = new PoseSmoother(true);
        SquatCounter counter = new SquatCounter(null);
        FrameGapGuard guard = new FrameGapGuard();
        guard.process(counter, smoother.smooth(translated(0, 168f, 0f, 0f)));
        long t = 0;
        PoseFrame out = null;
        for (int i = 1; i <= 12; i++) {
            t += 400; // 候选帧相隔 >350ms，即使 <500ms 也不能伪装成连续恢复
            out = smoother.smooth(translated(t, 168f, 0f, .16f + i * .008f));
            guard.process(counter, out);
        }
        assertFalse(out.isReliable(PoseFrame.LEFT_SHOULDER));
        assertEquals(0, counter.getGoodReps() + counter.getBadReps());
        for (int i = 0; i < 20; i++) {
            t += FPS_STEP_MS;
            out = smoother.smooth(translated(t, 168f, 0f, .26f + i * .008f));
            guard.process(counter, out);
        }
        assertTrue("更连续的帧应允许重新捕获", out.isReliable(PoseFrame.LEFT_SHOULDER));
        assertEquals(0, counter.getGoodReps() + counter.getBadReps());
    }

    @Test public void personalCalibrationStillCountsAndReplaysAfterPanRecovery() {
        PoseSmoother smoother = new PoseSmoother(true);
        SquatCounter counter = new SquatCounter(null);
        FrameGapGuard guard = new FrameGapGuard();
        List<PoseFrame> recorded = new ArrayList<>();
        List<Integer> good = new ArrayList<>(), bad = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 8; i++, t += FPS_STEP_MS) {
            append(smoother, counter, guard, recorded, good, bad, translated(t, 175f, 0f, -.12f));
        }
        float dx = 0f;
        for (int i = 0; i < 22; i++, t += FPS_STEP_MS) {
            dx = .04f + i * .008f;
            append(smoother, counter, guard, recorded, good, bad, translated(t, 175f, 0f, dx));
        }
        for (float a : new float[]{160, 150, 140, 130, 130, 130}) {
            for (int i = 0; i < 3; i++, t += FPS_STEP_MS) {
                append(smoother, counter, guard, recorded, good, bad,
                        translated(t, a, a < 150 ? .12f : 0f, dx));
            }
        }
        PoseFrame sample = translated(t - FPS_STEP_MS, 130f, .12f, dx);
        PersonalCalibration calibration = counter.capturePersonalCalibration(sample);
        assertNotNull("横移恢复后仍可采样个人目标", calibration);
        counter.applyCalibrationEvent(calibration);
        assertEquals(1, counter.getGoodReps());
        assertNull("同一次不能重复校准", counter.capturePersonalCalibration(sample));
        Session session = new Session();
        session.setExerciseType(ExerciseType.SQUAT);
        session.addCalibrationChange(recorded.size() - 1, calibration);
        good.set(good.size() - 1, counter.getGoodReps());
        for (float a : new float[]{140, 150, 160, 175, 175}) {
            for (int i = 0; i < 3; i++, t += FPS_STEP_MS) {
                append(smoother, counter, guard, recorded, good, bad,
                        translated(t, a, a < 150 ? .12f : 0f, dx));
            }
        }
        float beforeSecondPan = dx;
        for (int n = 0; n < 26; n++, t += FPS_STEP_MS) {
            dx = beforeSecondPan - .16f - n * .006f;
            append(smoother, counter, guard, recorded, good, bad, translated(t, 175f, 0f, dx));
        }
        assertEquals("第二次横移不能清空已有成绩或重记校准", 1, counter.getGoodReps());
        for (float a : new float[]{160, 150, 140, 130, 130, 130, 140, 150, 160, 175, 175}) {
            for (int i = 0; i < 3; i++, t += FPS_STEP_MS) {
                append(smoother, counter, guard, recorded, good, bad,
                        translated(t, a, a < 150 ? .12f : 0f, dx));
            }
        }
        assertEquals("第二次横移后原个人目标仍有效", 2, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        ReplayTimeline replay = ReplayTimeline.build(recorded, session);
        for (int i = 0; i < recorded.size(); i++) {
            assertEquals("校准事件及后续次数，第 " + i + " 帧", (int) good.get(i), replay.getGoodRepsAt(i));
            assertEquals("不标准次数，第 " + i + " 帧", (int) bad.get(i), replay.getBadRepsAt(i));
        }
    }

    @Test public void timestampRollbackClearsPanEvidenceButKeepsTheSquatPolicy() {
        PoseSmoother smoother = new PoseSmoother(true);
        smoother.smooth(translated(1000, 168f, 0f, 0f));
        for (int n = 1; n <= 5; n++) {
            smoother.smooth(translated(1000 + n * FPS_STEP_MS, 168f, 0f, .18f + n * .008f));
        }
        PoseFrame rollback = smoother.smooth(translated(10, 168f, 0f, .15f));
        assertTrue("时间回退应以新轨迹开始", rollback.isReliable(PoseFrame.LEFT_SHOULDER));
        assertFalse(smoother.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        PoseFrame out = null;
        for (int n = 1; n <= 22; n++) {
            out = smoother.smooth(translated(10 + n * FPS_STEP_MS, 168f, 0f, .31f + n * .008f));
        }
        assertNotNull(out);
        assertTrue("重置后仍需启用深蹲横移恢复", out.isReliable(PoseFrame.LEFT_SHOULDER));
        assertTrue(smoother.getReacquiredJumps() > 0);
    }

    @Test public void coherentPanCanUseOneCompleteSide() {
        PoseSmoother smoother = new PoseSmoother(true);
        PoseFrame start = lowerConfidence(translated(0, 168f, 0f, 0f), .05f,
                PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_ANKLE);
        smoother.smooth(start);
        PoseFrame out = null;
        for (int n = 1; n <= 20; n++) {
            PoseFrame raw = lowerConfidence(translated(n * FPS_STEP_MS, 168f, 0f, .16f + n * .008f),
                    .05f, PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_ANKLE);
            out = smoother.smooth(raw);
        }
        assertNotNull(out);
        assertTrue(out.isReliable(PoseFrame.LEFT_SHOULDER));
        assertTrue(out.isReliable(PoseFrame.LEFT_HIP));
        assertTrue(out.isReliable(PoseFrame.LEFT_ANKLE));
        assertTrue(smoother.getReacquiredJumps() > 0);
    }
}
