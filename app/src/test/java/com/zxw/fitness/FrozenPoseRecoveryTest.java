package com.zxw.fitness;

import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.FrozenPoseRecovery;
import org.junit.Test;
import static com.zxw.fitness.SyntheticPoses.*;
import static org.junit.Assert.*;

/** 合成轨迹只验证触发条件，不能证明真实 ML Kit 输出或手机动作的误报率。 */
public class FrozenPoseRecoveryTest {
    private static final int W = 720, H = 1280;

    private PoseFrame frame(long time, float shift, int... moving) {
        return shiftLandmarks(withFullVisibleSkeleton(standing(time)), shift, 0, moving);
    }

    private boolean feed(FrozenPoseRecovery guard, long start, int movingCount) {
        boolean triggered = false;
        for (int i = 0; i <= 20; i++) {
            int[] moving = movingCount == 1 ? new int[]{PoseFrame.LEFT_WRIST}
                    : new int[]{PoseFrame.LEFT_WRIST, PoseFrame.RIGHT_WRIST};
            boolean now = guard.observe(frame(start + i * 100, .002f * i, moving), W, H);
            if (i < 20) assertFalse("需要持续足够长时间", now);
            triggered |= now;
        }
        return triggered;
    }

    @Test public void onlyOneOrTwoMovingAndManyIdenticalForTwoSecondsTriggers() {
        assertTrue(feed(new FrozenPoseRecovery(), 0, 1));
        assertTrue(feed(new FrozenPoseRecovery(), 0, 2));
    }

    @Test public void stillPersonOrOrdinaryWholeBodyMovementDoesNotTrigger() {
        FrozenPoseRecovery still = new FrozenPoseRecovery(), walking = new FrozenPoseRecovery();
        for (int i = 0; i < 80; i++) {
            assertFalse(still.observe(withFullVisibleSkeleton(standing(i * 100L)), W, H));
            PoseFrame p = frame(i * 100L, i * .002f, PoseFrame.LEFT_SHOULDER,
                    PoseFrame.RIGHT_SHOULDER, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP,
                    PoseFrame.LEFT_KNEE, PoseFrame.RIGHT_KNEE);
            assertFalse(walking.observe(p, W, H));
        }
    }

    @Test public void uncertainDriftOrThirdMovingPointDisqualifiesWindow() {
        FrozenPoseRecovery drift = new FrozenPoseRecovery(), three = new FrozenPoseRecovery();
        for (int i = 0; i <= 20; i++) {
            PoseFrame p = frame(i * 100L, .002f * i, PoseFrame.LEFT_WRIST);
            p = shiftLandmarks(p, .0001f * i, 0, PoseFrame.LEFT_HIP);
            assertFalse(drift.observe(p, W, H));
            p = frame(i * 100L, .002f * i, PoseFrame.LEFT_WRIST,
                    PoseFrame.RIGHT_WRIST, PoseFrame.LEFT_KNEE);
            assertFalse(three.observe(p, W, H));
        }
    }

    @Test public void movingNoseAlsoDisqualifiesThreePointMotion() {
        FrozenPoseRecovery guard = new FrozenPoseRecovery();
        for (int i = 0; i <= 20; i++) {
            PoseFrame p = shiftLandmarks(withFullVisibleSkeleton(squat(i * 100L, 170f, 0)), .002f * i, 0,
                    PoseFrame.NOSE, PoseFrame.LEFT_WRIST, PoseFrame.RIGHT_WRIST);
            assertFalse(guard.observe(p, W, H));
        }
    }

    @Test public void missingChangingConfidenceAndOutOfFrameCannotCountAsFrozen() {
        FrozenPoseRecovery absent = new FrozenPoseRecovery(), confidence = new FrozenPoseRecovery();
        FrozenPoseRecovery outside = new FrozenPoseRecovery();
        for (int i = 0; i < 70; i++) {
            PoseFrame p = frame(i * 100L, .002f * i, PoseFrame.LEFT_WRIST);
            PoseFrame legsOnly = lowerConfidence(p, 0, PoseFrame.LEFT_SHOULDER,
                    PoseFrame.RIGHT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.RIGHT_ELBOW,
                    PoseFrame.LEFT_WRIST, PoseFrame.RIGHT_WRIST);
            assertFalse(absent.observe(legsOnly, W, H));
            PoseFrame alternating = lowerConfidence(p, i % 2 == 0 ? .2f : .9f, PoseFrame.LEFT_HIP);
            assertFalse(confidence.observe(alternating, W, H));
            PoseFrame out = shiftLandmarks(p, 2f, 0, PoseFrame.LEFT_SHOULDER,
                    PoseFrame.RIGHT_SHOULDER);
            assertFalse(outside.observe(out, W, H));
        }
    }

    @Test public void evenOneMissingOrWeakDisplayedPointDoesNotTrigger() {
        for (int missing : PoseFrame.LANDMARK_SUBSET) {
            FrozenPoseRecovery guard = new FrozenPoseRecovery();
            for (int i = 0; i <= 30; i++) {
                PoseFrame p = frame(i * 100L, i * .002f, PoseFrame.LEFT_WRIST);
                p = lowerConfidence(p, .2f, missing);
                assertFalse("missing point " + missing, guard.observe(p, W, H));
            }
        }
    }

    @Test public void gapsRollbackDuplicateTimesAndSizeChangesDiscardEvidence() {
        FrozenPoseRecovery guard = new FrozenPoseRecovery();
        for (int i = 0; i < 10; i++) assertFalse(guard.observe(frame(i * 100L, i * .002f,
                PoseFrame.LEFT_WRIST), W, H));
        assertFalse(guard.observe(frame(2000, .04f, PoseFrame.LEFT_WRIST), W, H)); // >500ms
        assertFalse(guard.observe(frame(2000, .04f, PoseFrame.LEFT_WRIST), W, H)); // duplicate
        assertFalse(guard.observe(frame(10, 0, PoseFrame.LEFT_WRIST), W, H)); // rollback
        for (int i = 1; i <= 20; i++) {
            assertFalse(guard.observe(frame(10 + i * 100L, i * .002f, PoseFrame.LEFT_WRIST),
                    i % 2 == 0 ? W : W + 1, H));
        }
    }

    @Test public void attemptsHaveCooldownAndResetStartsNewCameraLifecycle() {
        FrozenPoseRecovery guard = new FrozenPoseRecovery();
        assertTrue(feed(guard, 0, 1));
        for (int i = 1; i < 80; i++) {
            assertFalse(guard.observe(frame(2000 + i * 100L, .002f * i,
                    PoseFrame.LEFT_WRIST), W, H));
        }
        guard.reset();
        assertTrue(feed(guard, 0, 1));
    }

    @Test public void reidentificationRejectsOnlyLegsUnchangedOrWrongTimestamp() {
        FrozenPoseRecovery guard = new FrozenPoseRecovery();
        assertTrue(feed(guard, 0, 1));
        PoseFrame original = frame(2000, .04f, PoseFrame.LEFT_WRIST);
        assertFalse(guard.accepts(original, original, W, H));
        PoseFrame legsOnly = lowerConfidence(original, 0, PoseFrame.LEFT_SHOULDER,
                PoseFrame.RIGHT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.RIGHT_ELBOW);
        legsOnly = shiftLandmarks(legsOnly, .02f, 0, PoseFrame.LEFT_HIP,
                PoseFrame.RIGHT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.RIGHT_KNEE);
        assertFalse(guard.accepts(original, legsOnly, W, H));
        PoseFrame recovered = shiftLandmarks(original, .02f, 0, PoseFrame.LEFT_SHOULDER,
                PoseFrame.RIGHT_SHOULDER, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP);
        assertTrue(guard.accepts(original, recovered, W, H));
        PoseFrame wrongTime = shiftLandmarks(withFullVisibleSkeleton(standing(2001)), .02f, 0, PoseFrame.LEFT_SHOULDER,
                PoseFrame.RIGHT_SHOULDER, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP);
        assertFalse(guard.accepts(original, wrongTime, W, H));
    }
}
