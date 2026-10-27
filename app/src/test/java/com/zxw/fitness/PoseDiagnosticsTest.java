package com.zxw.fitness;

import com.zxw.fitness.analysis.FrameGapGuard;
import com.zxw.fitness.analysis.HandstandTimer;
import com.zxw.fitness.analysis.PlankTimer;
import com.zxw.fitness.analysis.PullUpCounter;
import com.zxw.fitness.analysis.PushUpCounter;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.RunStepCounter;
import com.zxw.fitness.analysis.SquatCounter;
import com.zxw.fitness.analysis.WallSitTimer;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.BodyMetrics;
import com.zxw.fitness.pose.PoseSceneDiagnostics;
import com.zxw.fitness.pose.PoseSceneResult;
import com.zxw.fitness.pose.PoseSmoother;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.zxw.fitness.SyntheticPoses.FPS_STEP_MS;
import static com.zxw.fitness.SyntheticPoses.handstand;
import static com.zxw.fitness.SyntheticPoses.lowerConfidence;
import static com.zxw.fitness.SyntheticPoses.plank;
import static com.zxw.fitness.SyntheticPoses.pullUp;
import static com.zxw.fitness.SyntheticPoses.pushUp;
import static com.zxw.fitness.SyntheticPoses.repProfile;
import static com.zxw.fitness.SyntheticPoses.running;
import static com.zxw.fitness.SyntheticPoses.shiftLandmarks;
import static com.zxw.fitness.SyntheticPoses.squat;
import static com.zxw.fitness.SyntheticPoses.wallSit;
import static org.junit.Assert.*;

/** 离线 JVM 测试：场景提示、可信关节、跳变恢复与常见遮挡。 */
public class PoseDiagnosticsTest {
    @Test public void showsSceneQualityAndMasksOffscreenPredictions() {
        PoseFrame normal = pushUp(0, 168f, 0f, false);
        PoseSceneResult ready = PoseSceneDiagnostics.inspect(normal, ExerciseType.PUSHUP, 720, 1280, false);
        assertEquals(PoseSceneResult.Quality.USABLE, ready.quality);
        assertEquals(12, ready.reliablePoints);
        assertEquals(12, ready.totalPoints);
        assertEquals(PoseSceneResult.Quality.WAITING,
                PoseSceneDiagnostics.inspect(PoseFrame.empty(0), ExerciseType.PUSHUP, 720, 1280, false).quality);

        PoseFrame raw = shiftLandmarks(normal, -.5f, 0f, PoseFrame.LEFT_WRIST);
        raw = shiftLandmarks(raw, .7f, 0f, PoseFrame.RIGHT_WRIST);
        PoseSceneResult problem = PoseSceneDiagnostics.inspect(raw, ExerciseType.PUSHUP, 720, 1280, false);
        assertEquals(PoseSceneResult.Quality.WARNING, problem.quality);
        assertTrue(problem.message.contains("出画"));
        PoseFrame bounded = raw.maskOutOfFrame(720, 1280);
        assertEquals(0f, bounded.getConfidence(PoseFrame.LEFT_WRIST), 0f);
        assertEquals(0f, bounded.getConfidence(PoseFrame.RIGHT_WRIST), 0f);
        assertEquals(.9f, raw.getConfidence(PoseFrame.LEFT_WRIST), .001f); // 原始帧未被修改
        assertEquals(.9f, bounded.getConfidence(PoseFrame.LEFT_SHOULDER), .001f);

        PoseFrame height = shiftLandmarks(normal, 0f, .75f, PoseFrame.LEFT_WRIST);
        height = shiftLandmarks(height, 0f, 1.15f, PoseFrame.RIGHT_WRIST);
        PoseFrame clipped = height.maskOutOfFrame(720, 1280);
        assertEquals(.9f, clipped.getConfidence(PoseFrame.LEFT_WRIST), .001f); // y=1.5 < 1280/720
        assertEquals(0f, clipped.getConfidence(PoseFrame.RIGHT_WRIST), 0f);   // y=1.9 已出画
    }

    @Test public void diagnosesMatchingSidesAndLegacyPixelCoordinates() {
        PoseFrame normal = pushUp(0, 168f, 0f, false);
        PoseFrame crossed = lowerConfidence(normal, .05f, PoseFrame.RIGHT_SHOULDER, PoseFrame.LEFT_HIP);
        assertFalse(BodyMetrics.of(crossed).valid);
        PoseSceneResult mismatch = PoseSceneDiagnostics.inspect(crossed, ExerciseType.PUSHUP, 720, 1280, false);
        assertTrue(mismatch.message.contains("同一侧"));
        PoseFrame partial = shiftLandmarks(normal, .35f, 0f, PoseFrame.RIGHT_SHOULDER);
        partial = lowerConfidence(partial, .05f, PoseFrame.RIGHT_HIP);
        BodyMetrics metrics = BodyMetrics.of(partial);
        assertTrue(metrics.valid);
        assertEquals(normal.getX(PoseFrame.LEFT_SHOULDER), metrics.shoulderX, .0001f);
        PoseFrame lowerSide = shiftLandmarks(normal, .32f, 0f, PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP);
        lowerSide = lowerConfidence(lowerSide, .6f, PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP);
        assertEquals(normal.getX(PoseFrame.LEFT_SHOULDER), BodyMetrics.of(lowerSide).shoulderX, .0001f);

        PoseFrame oneAnkle = lowerConfidence(normal, .05f, PoseFrame.RIGHT_ANKLE);
        assertTrue(PoseSceneDiagnostics.inspect(oneAnkle, ExerciseType.RUNNING, 720, 1280, false)
                .message.contains("双脚踝"));
        float[] x = new float[PoseFrame.LANDMARK_COUNT], y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT], c = new float[PoseFrame.LANDMARK_COUNT];
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            x[i] = normal.getX(i) * 720f; y[i] = normal.getY(i) * 720f;
            z[i] = normal.getZ(i) * 720f; c[i] = normal.getConfidence(i);
        }
        PoseFrame pixels = PoseFrame.of(0, x, y, z, c);
        PoseSceneResult legacy = PoseSceneDiagnostics.inspect(pixels, ExerciseType.PUSHUP, 720, 1280, true);
        PoseSceneResult normalized = PoseSceneDiagnostics.inspect(normal, ExerciseType.PUSHUP, 720, 1280, false);
        assertEquals(normalized.quality, legacy.quality);
        assertEquals(normalized.reliablePoints, legacy.reliablePoints);
        assertEquals(normalized.message, legacy.message);
    }

    @Test public void transientJumpIsRejectedThenStableNewPositionRecovers() {
        PoseSmoother smoother = new PoseSmoother();
        PoseFrame start = pushUp(0, 168f, 0f, false);
        smoother.smooth(start);
        PoseFrame spike = shiftLandmarks(pushUp(FPS_STEP_MS, 168f, 0f, false), .35f, 0f,
                PoseFrame.LEFT_WRIST);
        PoseFrame held = smoother.smooth(spike);
        assertTrue(smoother.getPendingJumps()[PoseFrame.LEFT_WRIST]);
        assertEquals(start.getX(PoseFrame.LEFT_WRIST), held.getX(PoseFrame.LEFT_WRIST), .0001f);
        smoother.smooth(pushUp(2 * FPS_STEP_MS, 168f, 0f, false));
        assertFalse(smoother.getPendingJumps()[PoseFrame.LEFT_WRIST]);
        assertEquals(0, smoother.getReacquiredJumps());

        smoother = new PoseSmoother(); smoother.smooth(start);
        PoseFrame result = null;
        for (int n = 1; n <= 7; n++) {
            result = smoother.smooth(shiftLandmarks(pushUp(n * FPS_STEP_MS, 168f, 0f, false),
                    .16f, 0f, PoseFrame.LANDMARK_SUBSET));
        }
        assertNotNull(result);
        assertFalse("旧位置不能一直当成可信姿态", result.isReliable(PoseFrame.LEFT_SHOULDER));
        assertTrue(smoother.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        for (int n = 8; n <= 15; n++) {
            result = smoother.smooth(shiftLandmarks(pushUp(n * FPS_STEP_MS, 168f, 0f, false),
                    .16f, 0f, PoseFrame.LANDMARK_SUBSET));
        }
        assertTrue(result.isReliable(PoseFrame.LEFT_SHOULDER));
        assertEquals(start.getX(PoseFrame.LEFT_SHOULDER) + .16f,
                result.getX(PoseFrame.LEFT_SHOULDER), .001f);
        assertFalse(smoother.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        assertTrue(smoother.getReacquiredJumps() > 0);
    }

    @Test public void unstableJumpDoesNotBecomeANewTrack() {
        PoseSmoother smoother = new PoseSmoother();
        smoother.smooth(pushUp(0, 168f, 0f, false));
        PoseFrame result = null;
        for (int n = 1; n < 28; n++) {
            result = smoother.smooth(shiftLandmarks(pushUp(n * FPS_STEP_MS, 168f, 0f, false),
                    n % 2 == 0 ? -.16f : .16f, 0f, PoseFrame.LANDMARK_SUBSET));
        }
        assertEquals(0, smoother.getReacquiredJumps());
        assertNotNull(result);
        assertFalse(result.isReliable(PoseFrame.LEFT_SHOULDER));
    }

    @Test public void missingCandidateAndClockRollbackResetJumpStability() {
        PoseSmoother smoother = new PoseSmoother();
        smoother.smooth(pushUp(0, 168f, 0f, false));
        for (int n = 1; n <= 5; n++) {
            smoother.smooth(shiftLandmarks(pushUp(n * FPS_STEP_MS, 168f, 0f, false),
                    .16f, 0f, PoseFrame.LANDMARK_SUBSET));
        }
        PoseFrame missing = shiftLandmarks(pushUp(6 * FPS_STEP_MS, 168f, 0f, false),
                .16f, 0f, PoseFrame.LANDMARK_SUBSET);
        smoother.smooth(lowerConfidence(missing, .05f, PoseFrame.LEFT_SHOULDER));
        PoseFrame result = null;
        for (int n = 7; n <= 15; n++) {
            result = smoother.smooth(shiftLandmarks(pushUp(n * FPS_STEP_MS, 168f, 0f, false),
                    .16f, 0f, PoseFrame.LANDMARK_SUBSET));
        }
        assertNotNull(result);
        assertTrue(smoother.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        assertFalse(result.isReliable(PoseFrame.LEFT_SHOULDER));
        for (int n = 16; n <= 20; n++) {
            result = smoother.smooth(shiftLandmarks(pushUp(n * FPS_STEP_MS, 168f, 0f, false),
                    .16f, 0f, PoseFrame.LANDMARK_SUBSET));
        }
        assertFalse(smoother.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        assertTrue(result.isReliable(PoseFrame.LEFT_SHOULDER));

        smoother = new PoseSmoother();
        smoother.smooth(pushUp(0, 168f, 0f, false));
        for (int n = 1; n <= 17; n++) smoother.smooth(PoseFrame.empty(n * FPS_STEP_MS));
        PoseFrame returned = shiftLandmarks(pushUp(18 * FPS_STEP_MS, 168f, 0f, false),
                .16f, 0f, PoseFrame.LANDMARK_SUBSET);
        result = smoother.smooth(returned);
        assertFalse(smoother.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        assertEquals(returned.getX(PoseFrame.LEFT_SHOULDER), result.getX(PoseFrame.LEFT_SHOULDER), .0001f);

        PoseFrame rollback = shiftLandmarks(pushUp(10, 168f, 0f, false), .16f, 0f, PoseFrame.LANDMARK_SUBSET);
        result = smoother.smooth(rollback);
        assertFalse(smoother.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        assertEquals(rollback.getX(PoseFrame.LEFT_SHOULDER), result.getX(PoseFrame.LEFT_SHOULDER), .0001f);
    }

    @Test public void jumpDoesNotCompleteUnfinishedPushUpButNormalRepsStillCount() {
        PoseSmoother smoother = new PoseSmoother();
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long t = 0;
        for (int n = 0; n < 5; n++) { counter.process(smoother.smooth(pushUp(t, 168f, 0f, false))); t += FPS_STEP_MS; }
        for (float angle : new float[]{155,145,135,125,115,105,95,90}) {
            for (int n = 0; n < 3; n++) { counter.process(smoother.smooth(pushUp(t, angle, 0f, false))); t += FPS_STEP_MS; }
        }
        for (int n = 0; n < 16; n++) {
            counter.process(smoother.smooth(shiftLandmarks(pushUp(t, 168f, 0f, false),
                    .16f, 0f, PoseFrame.LANDMARK_SUBSET)));
            t += FPS_STEP_MS;
        }
        assertEquals(0, counter.getGoodReps() + counter.getBadReps());

        smoother = new PoseSmoother(); counter = new PushUpCounter(ExerciseType.PUSHUP, "", null); t = 0;
        for (int n = 0; n < 5; n++) { counter.process(smoother.smooth(pushUp(t, 168f, 0f, false))); t += FPS_STEP_MS; }
        for (int rep = 0; rep < 3; rep++) {
            for (float angle : repProfile(90f, 168f, 8)) {
                counter.process(smoother.smooth(pushUp(t, angle, 0f, false))); t += FPS_STEP_MS;
            }
            for (int n = 0; n < 3; n++) { counter.process(smoother.smooth(pushUp(t, 168f, 0f, false))); t += FPS_STEP_MS; }
        }
        assertEquals(3, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
    }

    @Test public void filteredFramesReproduceCountsAcrossTrackingJump() {
        PoseSmoother smoother = new PoseSmoother();
        PushUpCounter live = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        List<PoseFrame> frames = new ArrayList<>();
        List<Integer> expected = new ArrayList<>();
        long t = 0;
        for (int n = 0; n < 5; n++) {
            PoseFrame f = smoother.smooth(pushUp(t, 168f, 0f, false)); t += FPS_STEP_MS;
            live.process(f); frames.add(f); expected.add(live.getGoodReps());
        }
        for (float a : new float[]{155,145,135,125,115,105,95,90}) {
            for (int n = 0; n < 3; n++) {
                PoseFrame f = smoother.smooth(pushUp(t, a, 0f, false)); t += FPS_STEP_MS;
                live.process(f); frames.add(f); expected.add(live.getGoodReps());
            }
        }
        for (int n = 0; n < 16; n++) {
            PoseFrame raw = shiftLandmarks(pushUp(t, 168f, 0f, false), .16f, 0f, PoseFrame.LANDMARK_SUBSET);
            PoseFrame f = smoother.smooth(raw); t += FPS_STEP_MS;
            live.process(f); frames.add(f); expected.add(live.getGoodReps());
        }
        ReplayTimeline timeline = ReplayTimeline.build(frames, ExerciseType.PUSHUP, "");
        for (int i = 0; i < frames.size(); i++) {
            assertEquals("frame " + i, expected.get(i).intValue(), timeline.getGoodRepsAt(i));
        }
        assertEquals(0, timeline.getGoodRepsAt(timeline.size() - 1));
    }

    @Test public void cameraGapIsNotCountedAndReplayMatches() {
        FrameGapGuard guard = new FrameGapGuard();
        PlankTimer timer = new PlankTimer(null);
        List<PoseFrame> frames = new ArrayList<>();
        for (int n = 0; n < 20; n++) {
            PoseFrame f = plank(n * FPS_STEP_MS, 0f, true);
            guard.process(timer, f); frames.add(f);
        }
        long beforeGap = timer.getBestHoldMs();
        assertTrue(beforeGap >= 1000);
        PoseFrame after = plank(19 * FPS_STEP_MS + 2000, 0f, true);
        guard.process(timer, after); frames.add(after);
        assertEquals(beforeGap, timer.getBestHoldMs());
        ReplayTimeline timeline = ReplayTimeline.build(frames, ExerciseType.PLANK, "");
        assertEquals(beforeGap, timeline.getHoldMsAt(timeline.size() - 1));

        guard = new FrameGapGuard(); timer = new PlankTimer(null);
        for (int n = 0; n < 3; n++) guard.process(timer, plank(n * FPS_STEP_MS, 0f, true));
        guard.process(timer, plank(2 * FPS_STEP_MS + 501, 0f, true));
        assertEquals(0, timer.getAttempts());
        assertEquals(0, timer.getBestHoldMs());
    }

    @Test public void cameraGapDoesNotCompleteARepAndReplayMatches() {
        FrameGapGuard pg = new FrameGapGuard(), sg = new FrameGapGuard(), ug = new FrameGapGuard();
        PushUpCounter push = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        SquatCounter squatCounter = new SquatCounter(null);
        PullUpCounter pull = new PullUpCounter(null);
        List<PoseFrame> pushFrames = new ArrayList<>();
        List<PoseFrame> squatFrames = new ArrayList<>();
        List<PoseFrame> pullFrames = new ArrayList<>();
        long t = 0;
        List<Float> profile = new ArrayList<>();
        for (int n = 0; n < 5; n++) profile.add(168f);
        for (float a : new float[]{155,145,135,125,115,105,95,90}) {
            for (int n = 0; n < 3; n++) profile.add(a);
        }
        for (float a : profile) {
            float depth = Math.max(0f, (168f - a) / 78f);
            PoseFrame p = pushUp(t, a, 0f, false), s = squat(t, a, depth * .6f), u = pullUp(t, a, depth * .7f);
            pg.process(push, p); sg.process(squatCounter, s); ug.process(pull, u);
            pushFrames.add(p); squatFrames.add(s); pullFrames.add(u); t += FPS_STEP_MS;
        }
        t += 2000;
        for (int n = 0; n < 6; n++) {
            PoseFrame p = pushUp(t, 168f, 0f, false), s = squat(t, 168f, 0f), u = pullUp(t, 168f, 0f);
            pg.process(push, p); sg.process(squatCounter, s); ug.process(pull, u);
            pushFrames.add(p); squatFrames.add(s); pullFrames.add(u); t += FPS_STEP_MS;
        }
        assertEquals(0, push.getGoodReps() + push.getBadReps());
        assertEquals(0, squatCounter.getGoodReps() + squatCounter.getBadReps());
        assertEquals(0, pull.getGoodReps() + pull.getBadReps());
        ReplayTimeline pt = ReplayTimeline.build(pushFrames, ExerciseType.PUSHUP, "");
        ReplayTimeline st = ReplayTimeline.build(squatFrames, ExerciseType.SQUAT, "");
        ReplayTimeline ut = ReplayTimeline.build(pullFrames, ExerciseType.PULLUP, "");
        assertEquals(0, pt.getGoodRepsAt(pt.size() - 1) + pt.getBadRepsAt(pt.size() - 1));
        assertEquals(0, st.getGoodRepsAt(st.size() - 1) + st.getBadRepsAt(st.size() - 1));
        assertEquals(0, ut.getGoodRepsAt(ut.size() - 1) + ut.getBadRepsAt(ut.size() - 1));
    }

    @Test public void prolongedPartialOcclusionDoesNotCompleteRepsOrInventSteps() {
        SquatCounter squatCounter = new SquatCounter(null);
        PullUpCounter pull = new PullUpCounter(null);
        long t = 0;
        for (int n = 0; n < 5; n++) {
            squatCounter.process(squat(t, 168f, 0f)); pull.process(pullUp(t, 168f, 0f)); t += FPS_STEP_MS;
        }
        for (float a : new float[]{155,145,135,125,115,105,95,90}) {
            float depth = Math.max(0f, (168f - a) / 78f);
            for (int n = 0; n < 3; n++) {
                squatCounter.process(squat(t, a, depth * .6f)); pull.process(pullUp(t, a, depth * .7f)); t += FPS_STEP_MS;
            }
        }
        for (int n = 0; n < 15; n++) {
            squatCounter.process(lowerConfidence(squat(t, 90f, .6f), .05f, PoseFrame.LEFT_KNEE, PoseFrame.RIGHT_KNEE));
            pull.process(lowerConfidence(pullUp(t, 90f, .7f), .05f, PoseFrame.LEFT_ELBOW, PoseFrame.RIGHT_ELBOW));
            t += FPS_STEP_MS;
        }
        for (int n = 0; n < 6; n++) {
            squatCounter.process(squat(t, 168f, 0f)); pull.process(pullUp(t, 168f, 0f)); t += FPS_STEP_MS;
        }
        assertEquals(0, squatCounter.getGoodReps() + squatCounter.getBadReps());
        assertEquals(0, pull.getGoodReps() + pull.getBadReps());

        RunStepCounter steps = new RunStepCounter(null); t = 0;
        for (int n = 0; n < 5; n++) { steps.process(running(t, .16f, 0f)); t += FPS_STEP_MS; }
        for (int n = 0; n < 5; n++) { steps.process(running(t, 0f, .16f)); t += FPS_STEP_MS; }
        int before = steps.getGoodReps();
        assertTrue(before > 0);
        for (int n = 0; n < 15; n++) {
            steps.process(lowerConfidence(running(t, 0f, .16f), .05f, PoseFrame.RIGHT_ANKLE)); t += FPS_STEP_MS;
        }
        for (int n = 0; n < 5; n++) { steps.process(running(t, .16f, 0f)); t += FPS_STEP_MS; }
        assertEquals(before, steps.getGoodReps());
    }

    @Test public void handstandRequiresVisibleRaisedAnkleAndWallSitToleratesOneHiddenKnee() {
        HandstandTimer missing = new HandstandTimer(ExerciseType.HANDSTAND, null);
        HandstandTimer feetDown = new HandstandTimer(ExerciseType.HANDSTAND, null);
        HandstandTimer oneFootDown = new HandstandTimer(ExerciseType.HANDSTAND, null);
        WallSitTimer wall = new WallSitTimer(null);
        for (int n = 0; n < 60; n++) {
            long ts = n * FPS_STEP_MS;
            PoseFrame body = handstand(ts, 0f, true);
            missing.process(lowerConfidence(body, .05f, PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE));
            feetDown.process(shiftLandmarks(body, 0f, .72f, PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE));
            oneFootDown.process(shiftLandmarks(body, 0f, .72f, PoseFrame.RIGHT_ANKLE));
            PoseFrame hiddenKnee = shiftLandmarks(wallSit(ts, 0f), 0f, -.7f, PoseFrame.RIGHT_KNEE);
            wall.process(lowerConfidence(hiddenKnee, .05f, PoseFrame.RIGHT_KNEE));
        }
        assertEquals(0, missing.getBestHoldMs());
        assertEquals(0, feetDown.getBestHoldMs());
        assertEquals(0, oneFootDown.getBestHoldMs());
        assertTrue(wall.getBestHoldMs() >= 3000);
    }
}
