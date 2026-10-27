package com.zxw.fitness;

import com.zxw.fitness.analysis.PullUpCounter;
import com.zxw.fitness.analysis.PushUpCounter;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.SquatCounter;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;
import com.zxw.fitness.pose.GravityAlign;
import com.zxw.fitness.pose.PoseMath;

import org.junit.Test;

import java.io.File;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static com.zxw.fitness.SyntheticPoses.FPS_STEP_MS;
import static com.zxw.fitness.SyntheticPoses.lowerConfidence;
import static com.zxw.fitness.SyntheticPoses.pullUp;
import static com.zxw.fitness.SyntheticPoses.pushUp;
import static com.zxw.fitness.SyntheticPoses.repProfile;
import static com.zxw.fitness.SyntheticPoses.squat;
import static org.junit.Assert.*;

/** 纯 JVM 测试：个人目标与即时计数、往返去重、账号隔离和帧级回放。 */
public class PersonalCalibrationTest {

    private static long preparePushBottom(PushUpCounter counter, long t, boolean kneel) {
        for (int i = 0; i < 5; i++) {
            counter.process(pushUp(t, 168f, 0f, kneel)); t += FPS_STEP_MS;
        }
        for (float a : new float[]{155, 145, 135, 125, 115}) {
            for (int i = 0; i < 3; i++) {
                counter.process(pushUp(t, a, 0f, kneel)); t += FPS_STEP_MS;
            }
        }
        return t;
    }

    private static long returnPushTop(PushUpCounter counter, long t, boolean kneel) {
        for (float a : new float[]{125, 135, 145, 155, 168, 168, 168}) {
            counter.process(pushUp(t, a, 0f, kneel)); t += FPS_STEP_MS;
        }
        return t;
    }

    private static long pushCycle(PushUpCounter counter, long t, float bottom, boolean kneel) {
        for (float a : repProfile(bottom, 168f, 8)) {
            counter.process(pushUp(t, a, 0f, kneel)); t += FPS_STEP_MS;
        }
        for (int i = 0; i < 3; i++) {
            counter.process(pushUp(t, 168f, 0f, kneel)); t += FPS_STEP_MS;
        }
        return t;
    }

    @Test
    public void shallowPushUpCountsImmediatelyAndAfterReturn() {
        PushUpCounter c = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        assertNull(c.capturePersonalCalibration(pushUp(0, 115f, 0f, false)));
        long t = preparePushBottom(c, 0, false);
        PoseFrame bottom = pushUp(t - FPS_STEP_MS, 115f, 0f, false);
        assertNull(c.capturePersonalCalibration(lowerConfidence(bottom, 0.05f,
                PoseFrame.LEFT_ELBOW, PoseFrame.RIGHT_ELBOW,
                PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE)));
        assertNull("滤波帧与当前原始姿态相差太大时应等待稳定",
                c.capturePersonalCalibration(pushUp(t - FPS_STEP_MS, 150f, 0f, false)));
        PersonalCalibration cal = c.capturePersonalCalibration(bottom);
        assertNotNull(cal);
        assertTrue(cal.isValid());
        assertEquals(115f, cal.getCapturedAngle(), 0.5f);
        c.applyCalibrationEvent(cal);
        assertEquals(1, c.getGoodReps());
        assertNull("同一次不能重复点击", c.capturePersonalCalibration(bottom));

        c.process(PoseFrame.empty(t + 1200)); t += 1266;
        c.process(pushUp(t, 115f, 0f, false)); t += FPS_STEP_MS;
        assertNull("长时间丢帧不应解锁重复点击", c.capturePersonalCalibration(pushUp(t, 115f, 0f, false)));
        t = returnPushTop(c, t, false);
        assertEquals(1, c.getGoodReps());
        t = pushCycle(c, t, 115f, false);
        assertEquals(2, c.getGoodReps());
        assertEquals(0, c.getBadReps());
        pushCycle(c, t, 130f, false);
        assertEquals("低于个人目标不算有效", 2, c.getGoodReps());

        PushUpCounter next = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        next.setPersonalCalibration(cal);
        pushCycle(next, 0, 115f, false);
        assertEquals(1, next.getGoodReps());
        PushUpCounter defaultCounter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        pushCycle(defaultCounter, 0, 115f, false);
        assertEquals(0, defaultCounter.getGoodReps());
        assertEquals(1, defaultCounter.getBadReps());
    }

    @Test
    public void sampledKneelingCanCountButStillWarns() {
        PushUpCounter c = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long t = preparePushBottom(c, 0, true);
        PersonalCalibration cal = c.capturePersonalCalibration(pushUp(t - FPS_STEP_MS, 115f, 0f, true));
        assertNotNull(cal);
        assertTrue(cal.isKneelingAllowed());
        assertTrue(cal.hasFormWarning());
        c.applyCalibrationEvent(cal);
        t = returnPushTop(c, t, true);
        pushCycle(c, t, 115f, true);
        assertEquals(2, c.getGoodReps());
        assertEquals(0, c.getBadReps());
        assertTrue(c.getFormErrors() >= 2);
    }

    @Test
    public void badPushUpUsesBadCountForManualAndSubsequentRep() {
        PushUpCounter c = new PushUpCounter(ExerciseType.BAD_PUSHUP, "", null);
        long t = preparePushBottom(c, 0, false);
        PersonalCalibration cal = c.capturePersonalCalibration(pushUp(t - FPS_STEP_MS, 115f, 0f, false));
        assertNotNull(cal);
        c.applyCalibrationEvent(cal);
        assertEquals(1, c.getBadReps());
        t = returnPushTop(c, t, false);
        pushCycle(c, t, 115f, false);
        assertEquals(2, c.getBadReps());
        assertEquals(0, c.getGoodReps());
    }

    @Test
    public void squatAndPullUpSupportShallowPersonalGoals() {
        SquatCounter squatCounter = new SquatCounter(null);
        long t = 0;
        for (int i = 0; i < 5; i++) {
            squatCounter.process(squat(t, 175f, 0f)); t += FPS_STEP_MS;
        }
        for (float a : new float[]{160, 150, 140, 130, 130, 130}) {
            for (int i = 0; i < 3; i++) {
                squatCounter.process(squat(t, a, a < 150 ? 0.12f : 0f)); t += FPS_STEP_MS;
            }
        }
        PersonalCalibration sc = squatCounter.capturePersonalCalibration(squat(t - FPS_STEP_MS, 130f, 0.12f));
        assertNotNull(sc);
        squatCounter.applyCalibrationEvent(sc);
        assertEquals(1, squatCounter.getGoodReps());
        assertNull(squatCounter.capturePersonalCalibration(squat(t, 130f, 0.12f)));
        for (float a : new float[]{140, 150, 160, 175, 175, 160, 150, 140, 130, 130, 130, 140, 150, 160, 175, 175}) {
            for (int i = 0; i < 3; i++) {
                squatCounter.process(squat(t, a, a < 150 ? 0.12f : 0f)); t += FPS_STEP_MS;
            }
        }
        assertEquals(2, squatCounter.getGoodReps());
        assertEquals(0, squatCounter.getBadReps());

        PullUpCounter pullCounter = new PullUpCounter(null);
        t = 0;
        for (int i = 0; i < 5; i++) {
            pullCounter.process(pullUp(t, 172f, 0f)); t += FPS_STEP_MS;
        }
        float[][] down = {{165, .02f}, {145, .1f}, {135, .15f}, {125, .2f}};
        for (float[] p : down) {
            for (int i = 0; i < 4; i++) {
                pullCounter.process(pullUp(t, p[0], p[1])); t += FPS_STEP_MS;
            }
        }
        PersonalCalibration pc = pullCounter.capturePersonalCalibration(pullUp(t - FPS_STEP_MS, 125f, .2f));
        assertNotNull(pc);
        pullCounter.applyCalibrationEvent(pc);
        assertEquals(1, pullCounter.getGoodReps());
        float[][] upAndCycle = {{135, .15f}, {145, .1f}, {165, .02f}, {172, 0},
                {165, .02f}, {145, .1f}, {135, .15f}, {125, .2f},
                {135, .15f}, {145, .1f}, {165, .02f}, {172, 0}};
        for (float[] p : upAndCycle) {
            for (int i = 0; i < 4; i++) {
                pullCounter.process(pullUp(t, p[0], p[1])); t += FPS_STEP_MS;
            }
        }
        assertEquals(2, pullCounter.getGoodReps());
        assertEquals(0, pullCounter.getBadReps());
    }

    @Test
    public void limitedStartingPositionDoesNotRequireStandardStraightening() {
        PushUpCounter push = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long t = 0;
        for (int i = 0; i < 8; i++) { push.process(pushUp(t, 140f, 0f, false)); t += FPS_STEP_MS; }
        assertNull(push.capturePersonalCalibration(pushUp(t - FPS_STEP_MS, 140f, 0f, false)));
        // 单帧误识别的伸直角不应被保存为个人起始位。
        push.process(pushUp(t, 175f, 0f, false)); t += FPS_STEP_MS;
        push.process(pushUp(t, 140f, 0f, false)); t += FPS_STEP_MS;
        for (float a : new float[]{138, 134, 130, 125, 120, 115, 115}) {
            for (int i = 0; i < 3; i++) { push.process(pushUp(t, a, 0f, false)); t += FPS_STEP_MS; }
        }
        PersonalCalibration pc = push.capturePersonalCalibration(pushUp(t - FPS_STEP_MS, 115f, 0f, false));
        assertNotNull(pc);
        assertEquals(140f, pc.getStartingAngle(), 0.5f);
        push.applyCalibrationEvent(pc);
        for (float a : new float[]{120, 125, 130, 135, 140, 140, 140}) {
            for (int i = 0; i < 2; i++) { push.process(pushUp(t, a, 0f, false)); t += FPS_STEP_MS; }
        }
        for (float a : repProfile(115f, 140f, 8)) {
            push.process(pushUp(t, a, 0f, false)); t += FPS_STEP_MS;
        }
        for (int i = 0; i < 4; i++) { push.process(pushUp(t, 140f, 0f, false)); t += FPS_STEP_MS; }
        assertEquals(2, push.getGoodReps());
        assertEquals(0, push.getBadReps());

        SquatCounter sq = new SquatCounter(null); t = 0;
        for (int i = 0; i < 8; i++) { sq.process(squat(t, 140f, 0f)); t += FPS_STEP_MS; }
        sq.process(squat(t, 175f, 0f)); t += FPS_STEP_MS;
        sq.process(squat(t, 140f, 0f)); t += FPS_STEP_MS;
        for (float a : new float[]{138, 134, 130, 125, 120, 115, 115}) {
            for (int i = 0; i < 3; i++) { sq.process(squat(t, a, a < 135 ? .12f : 0f)); t += FPS_STEP_MS; }
        }
        PersonalCalibration sc = sq.capturePersonalCalibration(squat(t - FPS_STEP_MS, 115f, .12f));
        assertNotNull(sc);
        assertEquals(140f, sc.getStartingAngle(), .5f);
        sq.applyCalibrationEvent(sc);
        for (float a : new float[]{120, 125, 130, 135, 140, 140, 140,
                138, 134, 130, 125, 120, 115, 115, 120, 125, 130, 135, 140, 140, 140}) {
            for (int i = 0; i < 3; i++) { sq.process(squat(t, a, a < 135 ? .12f : 0f)); t += FPS_STEP_MS; }
        }
        assertEquals(2, sq.getGoodReps());
        assertEquals(0, sq.getBadReps());

        PullUpCounter pull = new PullUpCounter(null); t = 0;
        for (int i = 0; i < 8; i++) { pull.process(pullUp(t, 140f, 0f)); t += FPS_STEP_MS; }
        pull.process(pullUp(t, 175f, 0f)); t += FPS_STEP_MS;
        pull.process(pullUp(t, 140f, 0f)); t += FPS_STEP_MS;
        float[][] down = {{138,.02f}, {134,.05f}, {130,.08f}, {125,.12f}, {120,.16f}, {115,.2f}};
        float[][] up = {{120,.16f}, {125,.12f}, {130,.08f}, {135,.04f}, {140,0}};
        for (float[] p : down) for (int i = 0; i < 4; i++) {
            pull.process(pullUp(t, p[0], p[1])); t += FPS_STEP_MS;
        }
        PersonalCalibration pullCal = pull.capturePersonalCalibration(pullUp(t - FPS_STEP_MS, 115f, .2f));
        assertNotNull(pullCal);
        assertEquals(140f, pullCal.getStartingAngle(), .5f);
        pull.applyCalibrationEvent(pullCal);
        float[][][] phases = {up, down, up};
        for (float[][] phase : phases) for (float[] p : phase) for (int i = 0; i < 4; i++) {
            pull.process(pullUp(t, p[0], p[1])); t += FPS_STEP_MS;
        }
        assertEquals(2, pull.getGoodReps());
        assertEquals(0, pull.getBadReps());
    }

    @Test
    public void profileIsolatesUsersExercisesAndChairVariants() {
        UserProfile a = new UserProfile("a", "A", 0, "A");
        UserProfile b = new UserProfile("b", "B", 0, "B");
        PersonalCalibration hands = new PersonalCalibration(ExerciseType.CHAIR_PUSHUP, "hands",
                .6f, 120f, .3f, .13f, 150f, 0f, false, false);
        PersonalCalibration feet = new PersonalCalibration(ExerciseType.CHAIR_PUSHUP, "feet",
                .8f, 110f, .4f, .13f, 150f, 0f, false, false);
        PersonalCalibration normal = new PersonalCalibration(ExerciseType.PUSHUP, "",
                .7f, 115f, .2f, .13f, 150f, 0f, false, false);
        a.setCalibration(hands); a.setCalibration(feet); a.setCalibration(normal);
        assertSame(hands, a.getCalibration(ExerciseType.CHAIR_PUSHUP, "hands"));
        assertSame(feet, a.getCalibration(ExerciseType.CHAIR_PUSHUP, "feet"));
        assertNull(b.getCalibration(ExerciseType.CHAIR_PUSHUP, "hands"));
        PushUpCounter wrongVariant = new PushUpCounter(ExerciseType.CHAIR_PUSHUP, "feet", null);
        wrongVariant.setPersonalCalibration(hands);
        pushCycle(wrongVariant, 0, 115f, false);
        assertEquals(0, wrongVariant.getGoodReps());
        a.clearCalibration(ExerciseType.CHAIR_PUSHUP, "hands");
        assertNull(a.getCalibration(ExerciseType.CHAIR_PUSHUP, "hands"));
        assertSame(feet, a.getCalibration(ExerciseType.CHAIR_PUSHUP, "feet"));
        assertSame(normal, a.getCalibration(ExerciseType.PUSHUP, ""));
    }

    @Test
    public void pixelCoordinatesPreserveAnglesAndRollKeepsVideoOverlayAligned() throws Exception {
        PoseFrame original = pushUp(1000, 115f, 0f, false);
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] confidence = new float[PoseFrame.LANDMARK_COUNT];
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            x[i] = original.getX(i) * 720f;
            y[i] = original.getY(i) * 720f;
            z[i] = original.getZ(i) * 720f;
            confidence[i] = original.getConfidence(i);
        }
        PoseFrame pixels = PoseFrame.of(1000, x, y, z, confidence);
        PoseFrame normalized = PoseFrame.fromPixelCoordinates(1000, x, y, z, confidence, 720);
        float pixelAngle = PoseMath.angle(pixels, PoseFrame.LEFT_SHOULDER,
                PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST);
        float normalizedAngle = PoseMath.angle(normalized, PoseFrame.LEFT_SHOULDER,
                PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST);
        assertEquals(pixelAngle, normalizedAngle, 0.01f);
        float roll = -30f;
        PoseFrame aligned = GravityAlign.rotate(normalized, roll, .5f, 1280f / (2f * 720f));
        File file = File.createTempFile("roll-pose", ".dat");
        file.deleteOnExit();
        PoseLogWriter writer = new PoseLogWriter(file, 720, 1280);
        writer.addFrame(aligned, roll);
        assertTrue(writer.flush());
        writer.close();
        assertFalse(writer.hasError());
        assertFalse(writer.flush());
        PoseLog log = PoseLog.load(file);
        assertNotNull(log);
        assertFalse(log.isLegacyPixelCoordinates());
        assertEquals(aligned.getX(PoseFrame.LEFT_SHOULDER),
                log.getFrames().get(0).getX(PoseFrame.LEFT_SHOULDER), .0001f);
        assertEquals(normalized.getX(PoseFrame.LEFT_SHOULDER),
                log.getDisplayFrames().get(0).getX(PoseFrame.LEFT_SHOULDER), .0001f);
        assertEquals(normalized.getY(PoseFrame.LEFT_SHOULDER),
                log.getDisplayFrames().get(0).getY(PoseFrame.LEFT_SHOULDER), .0001f);
    }

    @Test
    public void readsLegacyPixelPoseFiles() throws Exception {
        File file = File.createTempFile("legacy-pose", ".dat");
        file.deleteOnExit();
        PoseFrame frame = pushUp(0, 115f, 0f, false);
        DataOutputStream out = new DataOutputStream(new FileOutputStream(file));
        out.writeInt(PoseLogWriter.MAGIC); out.writeInt(1); out.writeInt(720); out.writeInt(1280);
        out.writeInt(0); out.writeByte(1);
        for (float value : frame.toCompactArray()) out.writeFloat(value);
        out.close();
        PoseLog log = PoseLog.load(file);
        assertNotNull(log);
        assertTrue(log.isLegacyPixelCoordinates());
        assertEquals(1, log.getDisplayFrames().size());
        assertEquals(frame.getX(PoseFrame.LEFT_SHOULDER),
                log.getDisplayFrames().get(0).getX(PoseFrame.LEFT_SHOULDER), .0001f);
    }

    @Test
    public void replayReproducesMidSessionCalibrationEventsFromPoseFile() throws Exception {
        List<PoseFrame> frames = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 5; i++) { frames.add(pushUp(t, 168f, 0f, false)); t += FPS_STEP_MS; }
        for (int i = 0; i < 3; i++) {
            for (float a : new float[]{155, 145, 135, 125, 115}) {
                frames.add(pushUp(t, a, 0f, false)); t += FPS_STEP_MS;
            }
        }
        int first = frames.size() - 1;
        for (float a : new float[]{125, 135, 145, 155, 168, 168, 168}) {
            frames.add(pushUp(t, a, 0f, false)); t += FPS_STEP_MS;
        }
        for (int i = 0; i < 3; i++) {
            for (float a : new float[]{155, 145, 135, 125, 110}) {
                frames.add(pushUp(t, a, 0f, false)); t += FPS_STEP_MS;
            }
        }
        int second = frames.size() - 1;
        for (float a : new float[]{125, 135, 145, 155, 168, 168, 168}) {
            frames.add(pushUp(t, a, 0f, false)); t += FPS_STEP_MS;
        }
        for (float a : repProfile(110f, 168f, 8)) {
            frames.add(pushUp(t, a, 0f, false)); t += FPS_STEP_MS;
        }
        for (int i = 0; i < 3; i++) { frames.add(pushUp(t, 168f, 0f, false)); t += FPS_STEP_MS; }

        Session session = new Session();
        session.setExerciseType(ExerciseType.PUSHUP);
        PushUpCounter live = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        int[] expected = new int[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            PoseFrame frame = frames.get(i);
            live.process(frame);
            if (i == first || i == second) {
                PersonalCalibration cal = live.capturePersonalCalibration(frame);
                assertNotNull(cal);
                session.addCalibrationChange(i, cal);
                live.applyCalibrationEvent(cal);
            }
            expected[i] = live.getGoodReps();
        }
        assertEquals(3, live.getGoodReps());
        File file = File.createTempFile("personal-pose", ".dat");
        file.deleteOnExit();
        PoseLogWriter writer = new PoseLogWriter(file, 720, 1280);
        for (PoseFrame frame : frames) writer.addFrame(frame);
        writer.close();
        PoseLog log = PoseLog.load(file);
        assertNotNull(log);
        ReplayTimeline timeline = ReplayTimeline.build(log.getFrames(), session);
        assertEquals(0, timeline.getGoodRepsAt(first - 1));
        assertEquals(1, timeline.getGoodRepsAt(first));
        assertEquals(2, timeline.getGoodRepsAt(second));
        for (int i = 0; i < frames.size(); i++) {
            assertEquals("frame " + i, expected[i], timeline.getGoodRepsAt(i));
        }
        assertEquals(3, timeline.getGoodRepsAt(timeline.size() - 1));
    }
}
