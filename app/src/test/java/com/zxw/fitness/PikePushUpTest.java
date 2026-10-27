package com.zxw.fitness;

import com.zxw.fitness.analysis.AlgorithmVersion;
import com.zxw.fitness.analysis.FrameGapGuard;
import com.zxw.fitness.analysis.PikePushUpCounter;
import com.zxw.fitness.analysis.PushUpCounter;
import com.zxw.fitness.analysis.RepEvent;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.HistoryGrowth;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.data.RecordedTimeline;
import com.zxw.fitness.data.RecordedTimeline.Writer;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;
import com.zxw.fitness.pose.PoseSceneDiagnostics;
import com.zxw.fitness.pose.PoseSceneResult;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** 几何合成帧只验证状态机，不等于真实视频、相机或姿态模型的识别精度。 */
public class PikePushUpTest {
    private static final float[] FULL = {170,170,170,170,145,135,120,100,90,90,105,120,135,145,170,170,170};
    private static final float[] PARTIAL = {170,170,170,170,145,135,125,120,120,125,135,145,170,170,170};
    private static final int[] LEFT = {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW,
            PoseFrame.LEFT_WRIST, PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE};
    private static final int[] RIGHT = {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW,
            PoseFrame.RIGHT_WRIST, PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};

    /** 按指定肘角几何构造侧视倒 V；下放时肩下移，手和脚保持不动。 */
    private static PoseFrame pike(long t, float angle, float drop, float hipY, float ankleY) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT], y = x.clone(), z = x.clone(), c = x.clone();
        float sx = .38f, sy = .55f + drop, wx = .33f, wy = .78f;
        float dx = wx - sx, dy = wy - sy;
        float length = (float) Math.hypot(dx, dy);
        float bulge = (float) (length / (2 * Math.tan(Math.toRadians(angle) / 2)));
        float ex = (sx + wx) / 2 - dy / length * bulge;
        float ey = (sy + wy) / 2 + dx / length * bulge;
        float[][] xy = {{sx,sy}, {ex,ey}, {wx,wy}, {.57f,hipY},
                {.675f,(hipY + ankleY) / 2}, {.78f,ankleY}};
        for (int i = 0; i < LEFT.length; i++) for (int index : new int[]{LEFT[i], RIGHT[i]}) {
            x[index] = xy[i][0]; y[index] = xy[i][1]; c[index] = .9f;
        }
        x[PoseFrame.NOSE] = .35f; y[PoseFrame.NOSE] = sy + .03f; c[PoseFrame.NOSE] = .9f;
        return PoseFrame.of(t, x, y, z, c);
    }

    static PoseFrame pike(long t, float angle, float drop) {
        return pike(t, angle, drop, .35f, .75f);
    }

    static float drop(float angle) { return Math.max(0f, (170f - angle) / 80f * .09f); }

    private static List<PoseFrame> sequence(long start, float[] angles) {
        List<PoseFrame> frames = new ArrayList<>();
        for (int i = 0; i < angles.length; i++) frames.add(pike(start + i * 100L, angles[i], drop(angles[i])));
        return frames;
    }

    private static void feed(PikePushUpCounter counter, List<PoseFrame> frames) {
        FrameGapGuard guard = new FrameGapGuard();
        for (PoseFrame frame : frames) guard.process(counter, frame);
    }

    private static PoseFrame withConfidence(PoseFrame f, int[] missing) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT], y = x.clone(), z = x.clone(), c = x.clone();
        for (int i = 0; i < x.length; i++) {
            x[i] = f.getX(i); y[i] = f.getY(i); z[i] = f.getZ(i); c[i] = f.getConfidence(i);
        }
        for (int index : missing) c[index] = 0f;
        return PoseFrame.of(f.getTimestampMs(), x, y, z, c);
    }

    private static PoseFrame transform(PoseFrame f, float scale, float ox, float oy, boolean mirror) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT], y = x.clone(), z = x.clone(), c = x.clone();
        for (int i = 0; i < x.length; i++) {
            x[i] = (mirror ? -f.getX(i) : f.getX(i)) * scale + ox;
            y[i] = f.getY(i) * scale + oy;
            c[i] = f.getConfidence(i);
        }
        return PoseFrame.of(f.getTimestampMs(), x, y, z, c);
    }

    @Test public void fullCyclesAndPartialCyclesHaveSeparateScoresAndEvents() {
        List<RepEvent> events = new ArrayList<>();
        PikePushUpCounter counter = new PikePushUpCounter(events::add);
        feed(counter, sequence(1000, FULL));
        feed(counter, sequence(4000, PARTIAL));
        assertEquals(1, counter.getGoodReps());
        assertEquals(1, counter.getBadReps());
        assertEquals(1, counter.getFormErrors());
        assertEquals(2, events.size());
        assertTrue(events.get(0).valid);
        assertFalse(events.get(1).valid);
        assertEquals("下放幅度不足", events.get(1).reason);
        assertEquals("1 次", counter.getScoreText());
    }

    @Test public void inputMustStartAtTopAndFinishAtTop() {
        PikePushUpCounter counter = new PikePushUpCounter(null);
        long t = 1000;
        for (int i = 8; i < FULL.length; i++) {
            counter.process(pike(t, FULL[i], drop(FULL[i]))); t += 100;
        }
        assertEquals(0, counter.getGoodReps());
        for (int i = 4; i < 10; i++) {
            counter.process(pike(t, FULL[i], drop(FULL[i]))); t += 100;
        }
        assertEquals(0, counter.getGoodReps()); // 未撑回
        for (int i = 10; i < FULL.length; i++) {
            counter.process(pike(t, FULL[i], drop(FULL[i]))); t += 100;
        }
        assertEquals(1, counter.getGoodReps());
    }

    @Test public void pushupsStandingAndElbowOnlyNoiseAreNotFoldedPushups() {
        PikePushUpCounter counter = new PikePushUpCounter(null);
        for (int i = 0; i < FULL.length; i++) {
            counter.process(SyntheticPoses.pushUp(1000 + i * 100L, FULL[i], 0, false, drop(FULL[i])));
        }
        for (int i = 0; i < FULL.length; i++) counter.process(SyntheticPoses.standing(4000 + i * 100L));
        for (int i = 0; i < FULL.length; i++) counter.process(pike(7000 + i * 100L, FULL[i], 0));
        assertEquals(0, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        assertTrue(counter.getStatusText().contains("下放"));
    }

    @Test public void fixedTranslationScaleAndMirrorDoNotChangeCounts() {
        for (boolean mirror : new boolean[]{false, true}) for (float scale : new float[]{.6f, 1f, 1.5f}) {
            PikePushUpCounter counter = new PikePushUpCounter(null);
            for (PoseFrame f : sequence(1000, FULL)) counter.process(transform(f, scale, 2, 1, mirror));
            assertEquals("scale=" + scale + " mirror=" + mirror, 1, counter.getGoodReps());
            assertEquals(0, counter.getBadReps());
        }
    }

    @Test public void requiresACompleteReliableSideRatherThanMixingLeftAndRight() {
        for (int missing = 0; missing < LEFT.length; missing++) {
            PikePushUpCounter counter = new PikePushUpCounter(null);
            for (PoseFrame f : sequence(1000, FULL)) {
                counter.process(withConfidence(f, new int[]{LEFT[missing], RIGHT[missing]}));
            }
            assertEquals("missing joint " + missing, 0, counter.getGoodReps());
            assertEquals(0, counter.getBadReps());
        }
        PikePushUpCounter split = new PikePushUpCounter(null);
        for (PoseFrame f : sequence(1000, FULL)) split.process(withConfidence(f,
                new int[]{RIGHT[0], RIGHT[1], RIGHT[2], LEFT[3], LEFT[4], LEFT[5]}));
        assertEquals(0, split.getGoodReps());
        PikePushUpCounter oneSide = new PikePushUpCounter(null);
        for (PoseFrame f : sequence(1000, FULL)) oneSide.process(withConfidence(f, RIGHT));
        assertEquals(1, oneSide.getGoodReps());
    }

    @Test public void gapDuringRepDiscardsItButShortMissingFrameIsTolerated() {
        List<PoseFrame> frames = sequence(1000, FULL);
        PikePushUpCounter interrupted = new PikePushUpCounter(null);
        FrameGapGuard guard = new FrameGapGuard();
        for (int i = 0; i <= 9; i++) guard.process(interrupted, frames.get(i));
        for (int i = 10; i < FULL.length; i++) {
            PoseFrame f = pike(frames.get(i).getTimestampMs() + 1000, FULL[i], drop(FULL[i]));
            guard.process(interrupted, f);
        }
        assertEquals(0, interrupted.getGoodReps());
        assertEquals(0, interrupted.getBadReps());
        PikePushUpCounter shortDropout = new PikePushUpCounter(null);
        for (int i = 0; i < frames.size(); i++) {
            shortDropout.process(i == 8 ? PoseFrame.empty(frames.get(i).getTimestampMs()) : frames.get(i));
        }
        assertEquals(1, shortDropout.getGoodReps());
    }

    @Test public void openingHipsWhileStillPartlyInvertedIsMarkedForAdjustment() {
        List<RepEvent> events = new ArrayList<>();
        PikePushUpCounter counter = new PikePushUpCounter(events::add);
        for (int i = 0; i < FULL.length; i++) {
            counter.process(pike(1000 + i * 100L, FULL[i], drop(FULL[i]), .512f, .56f));
        }
        assertEquals(0, counter.getGoodReps());
        assertEquals(1, counter.getBadReps());
        assertEquals("髋部抬高不足", events.get(0).reason);
    }

    @Test public void calibrationCountsOnceWaitsForTopAndThenUsesPersonalGoal() {
        PikePushUpCounter counter = new PikePushUpCounter(null);
        long t = 1000;
        for (int i = 0; i < 4; i++, t += 100) counter.process(pike(t, 170, 0));
        for (float a : new float[]{150,145,140,135,135,135,135}) {
            counter.process(pike(t, a, drop(a))); t += 100;
        }
        PoseFrame raw = pike(t - 100, 135, drop(135));
        PersonalCalibration calibration = counter.capturePersonalCalibration(raw);
        assertNotNull(counter.getCalibrationError(), calibration);
        assertTrue(calibration.isValid());
        assertEquals(ExerciseType.PIKE_PUSHUP, calibration.getType());
        assertTrue(calibration.hasFormWarning());
        assertTrue(calibration.describe().contains("肩髋踝角"));
        counter.applyCalibrationEvent(calibration);
        assertEquals(1, counter.getGoodReps());
        assertNull(counter.capturePersonalCalibration(raw));
        // 即使校准后长时间无帧，也不能在原位置再次计数。
        t += 1200;
        for (int i = 0; i < 6; i++, t += 100) counter.process(pike(t, 135, drop(135)));
        assertEquals(1, counter.getGoodReps());
        for (int i = 0; i < 4; i++, t += 100) counter.process(pike(t, 170, 0));
        for (float a : new float[]{150,145,140,135,135,135,140,145,150,170,170}) {
            counter.process(pike(t, a, drop(a))); t += 100;
        }
        assertEquals(2, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        assertTrue(counter.getFormErrors() >= 1);
        UserProfile user = new UserProfile("kid", "kid", 1, "🌟");
        user.setCalibration(calibration);
        assertNotNull(user.getCalibration(ExerciseType.PIKE_PUSHUP, ""));
        assertNull(user.getCalibration(ExerciseType.PUSHUP, ""));
        assertNull(user.getCalibration(ExerciseType.BAD_PUSHUP, ""));
    }

    @Test public void refusesCalibrationWithoutStableMovementOrRawSide() {
        PikePushUpCounter c = new PikePushUpCounter(null);
        for (int i = 0; i < 6; i++) c.process(pike(1000 + i * 100L, 170, 0));
        assertNull(c.capturePersonalCalibration(pike(1500, 170, 0)));
        c.process(pike(1600, 145, drop(145)));
        c.process(pike(1700, 135, drop(135)));
        assertNull(c.capturePersonalCalibration(pike(1700, 135, drop(135))));
        for (int i = 0; i < 4; i++) c.process(pike(1800 + i * 100L, 135, drop(135)));
        PoseFrame raw = withConfidence(pike(2100, 135, drop(135)),
                new int[]{PoseFrame.LEFT_WRIST, PoseFrame.RIGHT_WRIST});
        assertNull(c.capturePersonalCalibration(raw));
        assertNotNull(c.capturePersonalCalibration(pike(2100, 135, drop(135))));
    }

    @Test public void recordedAndVideoReanalysisUsePikeInsteadOfOrdinaryPushup() {
        List<PoseFrame> frames = sequence(1000, FULL);
        Session session = new Session(); session.setExerciseType(ExerciseType.PIKE_PUSHUP);
        ReplayTimeline skeleton = ReplayTimeline.build(frames, session);
        ReplayTimeline video = ReplayTimeline.buildForVideo(frames, session);
        int last = frames.size() - 1;
        assertEquals(1, skeleton.getGoodRepsAt(last));
        assertEquals(1, video.getGoodRepsAt(last));
        assertEquals(1f, video.getScoreAt(last), .001f);
        ReplayTimeline wrong = ReplayTimeline.build(frames, ExerciseType.PUSHUP, "");
        assertNotEquals("不能误用普通俯卧撑计数器", wrong.getStatusAt(0), skeleton.getStatusAt(0));
    }

    @Test public void perExerciseVersionPreservesOldCachesButNotPikeMismatches() {
        Session old = new Session(); old.setExerciseType(ExerciseType.PUSHUP);
        old.setAlgorithmVersion(AlgorithmVersion.CURRENT);
        assertTrue(AlgorithmVersion.matches(old));
        old.setExerciseType(ExerciseType.BAD_PUSHUP);
        assertTrue(AlgorithmVersion.matches(old));
        old.setExerciseType(ExerciseType.PIKE_PUSHUP);
        assertFalse(AlgorithmVersion.matches(old));
        old.setAlgorithmVersion(AlgorithmVersion.forExercise(ExerciseType.PIKE_PUSHUP));
        assertTrue(AlgorithmVersion.matches(old));
    }
    @Test public void legacyHistoryKeepsItsOwnScoreWhileGroupingWithPushups() {
        List<ExerciseType> visible = Arrays.asList(ExerciseType.selectableValues());
        assertTrue(visible.contains(ExerciseType.PUSHUP));
        assertTrue(visible.contains(ExerciseType.PIKE_PUSHUP));
        assertFalse(visible.contains(ExerciseType.BAD_PUSHUP));
        assertTrue(ExerciseType.sameCatalog("pushup", "bad_pushup"));
        assertFalse(ExerciseType.sameCatalog("pushup", "pike_pushup"));
        assertFalse(ExerciseType.sameCatalog("pushup", "unknown"));
        assertFalse(ExerciseType.sameCatalog(null, "pushup"));
        assertTrue(ExerciseType.BAD_PUSHUP.historyTitle().contains("旧"));

        Session legacy = new Session(); legacy.setExerciseType(ExerciseType.BAD_PUSHUP);
        legacy.setStartedAt(1000); legacy.setReps(3); legacy.setBadReps(12);
        legacy.setPoseFile("old-pose.dat"); legacy.setVideoFile("old-video.mp4");
        Session normal = new Session(); normal.setExerciseType(ExerciseType.PUSHUP);
        normal.setStartedAt(1100); normal.setReps(5); normal.setBadReps(1);
        Session pike = new Session(); pike.setExerciseType(ExerciseType.PIKE_PUSHUP);
        pike.setStartedAt(1200); pike.setReps(7);
        assertEquals(12, legacy.getResultValue(), 0f); // 历史大字仍是不标准次数
        assertEquals(3, legacy.getCatalogBestValue(), 0f); // 列表最佳只比较原有有效次数
        assertEquals("不标准 12 次（旧记录）", legacy.getResultSummary());
        assertEquals("old-pose.dat", legacy.getPoseFile());
        assertEquals("old-video.mp4", legacy.getVideoFile());
        HistoryGrowth growth = HistoryGrowth.from(Arrays.asList(legacy, normal, pike),
                2000, TimeZone.getTimeZone("UTC"));
        assertEquals(3, growth.getRecordCount());
        assertEquals(2, growth.getExerciseTypes());
        assertEquals(1, growth.getActiveDays());
        UserProfile user = new UserProfile("kid", "kid", 1, "🌟");
        PersonalCalibration oldCalibration = new PersonalCalibration(ExerciseType.BAD_PUSHUP,
                "", .6f, 120f, .2f, .15f, 150f, 0f, false, true);
        user.setCalibration(oldCalibration);
        assertNotNull(user.getCalibration(ExerciseType.BAD_PUSHUP, ""));
        assertNull("不把旧待调整模式的目标静默套用于标准模式",
                user.getCalibration(ExerciseType.PUSHUP, ""));
    }

    @Test public void realPikePoseLogAndVersionedCachePreserveCalibrationAndReplay() throws Exception {
        File dir = Files.createTempDirectory("pike-timeline").toFile();
        File pose = new File(dir, "pose.dat"), cache = new File(dir, "timeline.dat");
        try {
            Session session = new Session(); session.setId(77); session.setUserId("kid");
            session.setExerciseType(ExerciseType.PIKE_PUSHUP);
            session.setAlgorithmVersion(AlgorithmVersion.forExercise(session.getExerciseType()));
            PikePushUpCounter counter = new PikePushUpCounter(null);
            PoseLogWriter poseWriter = new PoseLogWriter(pose, 720, 1280);
            Writer cacheWriter = new Writer(cache, session);
            List<ReplayTimeline.FrameState> expected = new ArrayList<>();
            float[] angles = {170,170,170,170,150,145,140,135,135,135,135,
                    140,145,150,170,170,170,170,150,145,140,135,135,135,140,145,150,170,170};
            for (int i = 0; i < angles.length; i++) {
                PoseFrame f = pike(1000 + i * 100L, angles[i], drop(angles[i]));
                poseWriter.addFrame(f, 0f);
                counter.process(f);
                ReplayTimeline.FrameState state = ReplayTimeline.capture(ExerciseType.PIKE_PUSHUP, counter, null);
                cacheWriter.addFrame(f, state);
                if (i == 10) {
                    PersonalCalibration calibration = counter.capturePersonalCalibration(f);
                    assertNotNull(calibration);
                    session.addCalibrationChange(i, calibration);
                    counter.applyCalibrationEvent(calibration);
                    state = ReplayTimeline.capture(ExerciseType.PIKE_PUSHUP, counter, null);
                    cacheWriter.addCalibration(state);
                }
                expected.add(state);
            }
            poseWriter.close();
            session.setReps(counter.getGoodReps()); session.setBadReps(counter.getBadReps());
            assertEquals(2, session.getReps());
            assertTrue(cacheWriter.finish(pose, session, angles.length));
            PoseLog log = PoseLog.load(pose);
            assertNotNull(log);
            ReplayTimeline restored = RecordedTimeline.load(cache, pose, log, session);
            assertNotNull("新动作也能复用自己的版本缓存", restored);
            ReplayTimeline recomputed = ReplayTimeline.build(log.getFrames(), session);
            for (int i = 0; i < angles.length; i++) {
                assertEquals("saved good @" + i, expected.get(i).good, restored.getGoodRepsAt(i));
                assertEquals("recomputed good @" + i, expected.get(i).good, recomputed.getGoodRepsAt(i));
                assertEquals("saved bad @" + i, expected.get(i).bad, restored.getBadRepsAt(i));
                assertEquals("recomputed bad @" + i, expected.get(i).bad, recomputed.getBadRepsAt(i));
                assertEquals("saved state @" + i, expected.get(i).status, restored.getStatusAt(i));
            }
            session.setAlgorithmVersion(AlgorithmVersion.CURRENT);
            assertNull("旧动作版本不能冒充折刀版本", RecordedTimeline.load(cache, pose, log, session));
        } finally {
            pose.delete(); cache.delete(); dir.delete();
        }
    }

    @Test public void limitedStartingExtensionCanBeCalibratedWithoutDefaultTop() {
        PikePushUpCounter counter = new PikePushUpCounter(null);
        long t = 1000;
        for (int i = 0; i < 6; i++, t += 100) counter.process(pike(t, 130, drop(130)));
        for (float a : new float[]{125,120,115,110,110,110,110,110}) {
            counter.process(pike(t, a, drop(a))); t += 100;
        }
        assertEquals(0, counter.getGoodReps());
        PersonalCalibration personal = counter.capturePersonalCalibration(pike(t - 100, 110, drop(110)));
        assertNotNull(counter.getCalibrationError(), personal);
        assertTrue("未达到默认撑起角应保留提醒", personal.hasFormWarning());
        counter.applyCalibrationEvent(personal);
        assertEquals(1, counter.getGoodReps());
        for (float a : new float[]{120,125,130,130,130,130,125,120,115,110,110,110,115,120,125,130,130}) {
            counter.process(pike(t, a, drop(a))); t += 100;
        }
        assertEquals(2, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        assertTrue(counter.getFormErrors() >= 2);
    }

    @Test public void invalidPikeTargetsCannotDisableMovementOrInversionLimits() {
        PersonalCalibration noMovement = new PersonalCalibration(ExerciseType.PIKE_PUSHUP, "", .5f,
                120, .1f, 145, 0, 0, false, false, 170);
        PersonalCalibration flat = new PersonalCalibration(ExerciseType.PIKE_PUSHUP, "", .5f,
                120, .1f, 180, 0, .02f, false, false, 170);
        assertFalse(noMovement.isValid()); assertFalse(flat.isValid());
        PikePushUpCounter counter = new PikePushUpCounter(null);
        counter.setPersonalCalibration(noMovement);
        counter.applyCalibrationEvent(flat);
        assertEquals(0, counter.getGoodReps());
        feed(counter, sequence(1000, PARTIAL));
        assertEquals(0, counter.getGoodReps());
        assertEquals(1, counter.getBadReps());
    }

    @Test public void timeRollbackOrFastNoiseNeverCompleteInterruptedRep() {
        PikePushUpCounter c = new PikePushUpCounter(null);
        List<PoseFrame> frames = sequence(1000, FULL);
        for (int i = 0; i <= 9; i++) c.process(frames.get(i));
        for (int i = 10; i < FULL.length; i++) c.process(pike(i * 50L, FULL[i], drop(FULL[i])));
        assertEquals(0, c.getGoodReps()); assertEquals(0, c.getBadReps());
        PikePushUpCounter fast = new PikePushUpCounter(null);
        for (int i = 0; i < FULL.length; i++) fast.process(pike(1000 + i * 15L, FULL[i], drop(FULL[i])));
        assertEquals(0, fast.getGoodReps()); assertEquals(0, fast.getBadReps());
    }

    @Test public void oldBadPushupReplayStillUsesBadRepAsPrimaryScore() {
        List<PoseFrame> frames = new ArrayList<>();
        long t = 0;
        for (float angle : SyntheticPoses.repProfile(110, 168, 8)) {
            frames.add(SyntheticPoses.pushUp(t, angle, 0, false)); t += SyntheticPoses.FPS_STEP_MS;
        }
        for (int i = 0; i < 3; i++, t += SyntheticPoses.FPS_STEP_MS)
            frames.add(SyntheticPoses.pushUp(t, 168, 0, false));
        PushUpCounter direct = new PushUpCounter(ExerciseType.BAD_PUSHUP, "", null);
        for (PoseFrame frame : frames) direct.process(frame);
        assertEquals(1, direct.getBadReps()); assertEquals(0, direct.getGoodReps());
        ReplayTimeline legacy = ReplayTimeline.build(frames, ExerciseType.BAD_PUSHUP, "");
        int last = frames.size() - 1;
        assertEquals(1f, legacy.getScoreAt(last), .001f);
        assertEquals(1, legacy.getBadRepsAt(last));
        assertEquals(0, legacy.getGoodRepsAt(last));
        ReplayTimeline normal = ReplayTimeline.build(frames, ExerciseType.PUSHUP, "");
        assertEquals(0f, normal.getScoreAt(last), .001f);
        assertEquals(1, normal.getBadRepsAt(last));
    }

    @Test public void initialTopMustBeConsecutiveDespitePreviouslyStableBottom() {
        PikePushUpCounter counter = new PikePushUpCounter(null);
        long t = 1000;
        for (int i = 0; i < 4; i++, t += 100) counter.process(pike(t, 140, drop(140)));
        for (int i = 0; i < 2; i++, t += 100) counter.process(pike(t, 170, 0));
        counter.process(PoseFrame.empty(t)); t += 100;
        counter.process(pike(t, 170, 0)); t += 100;
        for (float angle : new float[]{145,135,120,100,90,90,105,120,135,145,170,170,170}) {
            counter.process(pike(t, angle, drop(angle))); t += 100;
        }
        assertEquals(0, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        for (int i = 4; i < FULL.length; i++, t += 100)
            counter.process(pike(t, FULL[i], drop(FULL[i])));
        assertEquals(1, counter.getGoodReps());
    }

    @Test public void sceneDiagnosisExplainsWhenOnlyMixedSidesAreVisible() {
        PoseFrame frame = pike(1000, 170, 0);
        frame = withConfidence(frame, new int[]{RIGHT[0], RIGHT[1], RIGHT[2], LEFT[4], LEFT[5]});
        PoseSceneResult diagnosis = PoseSceneDiagnostics.inspect(frame, ExerciseType.PIKE_PUSHUP, 720, 1280, false);
        assertEquals(PoseSceneResult.Quality.WARNING, diagnosis.quality);
        assertTrue(diagnosis.message, diagnosis.message.contains("同侧"));
    }

    @Test public void hipsBelowShouldersAreRejectedEvenWhenHipAngleAndArmsMove() {
        PikePushUpCounter counter = new PikePushUpCounter(null);
        for (int i = 0; i < FULL.length; i++) {
            float d = drop(FULL[i]);
            counter.process(pike(1000 + i * 100L, FULL[i], d, .57f + d, .75f + d));
        }
        assertEquals(0, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        assertTrue(counter.getStatusText().contains("倒 V"));
    }

    @Test public void legacyBadRecordStillReusesItsUnchangedVersionedCache() throws Exception {
        File dir = Files.createTempDirectory("old-bad-cache").toFile();
        File pose = new File(dir, "pose.dat"), cache = new File(dir, "timeline.dat");
        try {
            Session old = new Session(); old.setId(99); old.setUserId("kid");
            old.setExerciseType(ExerciseType.BAD_PUSHUP);
            old.setAlgorithmVersion(AlgorithmVersion.CURRENT);
            PoseLogWriter poseWriter = new PoseLogWriter(pose, 720, 1280);
            Writer cacheWriter = new Writer(cache, old);
            PushUpCounter counter = new PushUpCounter(ExerciseType.BAD_PUSHUP, "", null);
            long t = 1000;
            int count = 0;
            for (float angle : SyntheticPoses.repProfile(110, 168, 8)) {
                PoseFrame f = SyntheticPoses.pushUp(t, angle, 0, false); t += 100; count++;
                poseWriter.addFrame(f); counter.process(f);
                cacheWriter.addFrame(f, ReplayTimeline.capture(ExerciseType.BAD_PUSHUP, counter, null));
            }
            for (int i = 0; i < 3; i++, t += 100) {
                PoseFrame f = SyntheticPoses.pushUp(t, 168, 0, false); count++;
                poseWriter.addFrame(f); counter.process(f);
                cacheWriter.addFrame(f, ReplayTimeline.capture(ExerciseType.BAD_PUSHUP, counter, null));
            }
            poseWriter.close(); old.setReps(counter.getGoodReps()); old.setBadReps(counter.getBadReps());
            assertEquals(0, old.getReps()); assertEquals(1, old.getBadReps());
            assertTrue(cacheWriter.finish(pose, old, count));
            PoseLog log = PoseLog.load(pose);
            ReplayTimeline recorded = RecordedTimeline.load(cache, pose, log, old);
            assertNotNull("旧判定器未改变时不应使缓存失效", recorded);
            assertEquals(1f, recorded.getScoreAt(count - 1), .001f);
            assertEquals(0, recorded.getGoodRepsAt(count - 1));
            assertEquals(1, recorded.getBadRepsAt(count - 1));
            assertEquals(recorded.getScoreAt(count - 1),
                    ReplayTimeline.build(log.getFrames(), old).getScoreAt(count - 1), .001f);
            assertEquals("不标准 1 次（旧记录）", old.getResultSummary());
        } finally { pose.delete(); cache.delete(); dir.delete(); }
    }

}
