package com.zxw.fitness;

import com.zxw.fitness.analysis.FrameGapGuard;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.SquatCounter;
import com.zxw.fitness.analysis.VideoPoseReanalysis;
import com.zxw.fitness.analysis.VideoReanalysisRunner;
import com.zxw.fitness.analysis.VideoReanalysisRunner.ImageFrame;
import com.zxw.fitness.camera.VideoFrameOrientation;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.pose.PoseSmoother;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.zxw.fitness.SyntheticPoses.*;
import static org.junit.Assert.*;

/** 不含 Android 或 ML Kit：模拟抽帧/识别器，检查真正的流水线，不替代设备录像测试。 */
public class VideoReanalysisRunnerTest {
    private interface PoseForTime { PoseFrame at(long timeMs); }

    private static Session session(ExerciseType type) {
        Session session = new Session();
        session.setExerciseType(type);
        session.setPoseFile("old-pose.dat");
        session.setVideoFile("original.mp4");
        session.setReps(73);
        session.setBadReps(4);
        session.setDurationMs(12345);
        return session;
    }

    private static class FakeFactory implements VideoReanalysisRunner.Factory<Long>,
            VideoReanalysisRunner.FrameSource<Long>, VideoReanalysisRunner.PoseEngine<Long> {
        long duration = 450;
        PoseForTime poses = t -> squat(t, 170f, 0f);
        final List<Long> requested = new ArrayList<>();
        final List<Long> detected = new ArrayList<>();
        final List<Long> closedImages = new ArrayList<>();
        boolean sourceClosed;
        boolean engineClosed;
        Boolean everyFrame;
        long missingAt = -1;
        long failAt = -1;
        long aspectChangeAt = -1;
        boolean missingAll;
        boolean cancelOnDetection;
        boolean cancelled;

        @Override public VideoReanalysisRunner.FrameSource<Long> openSource() { return this; }
        @Override public VideoReanalysisRunner.PoseEngine<Long> openEngine(boolean every) {
            everyFrame = every;
            return this;
        }
        @Override public long durationMs() { return duration; }
        @Override public ImageFrame<Long> frameAt(final long timeMs) {
            requested.add(timeMs);
            if (missingAll || timeMs == missingAt) return null;
            return new ImageFrame<Long>() {
                @Override public Long image() { return timeMs; }
                @Override public int width() { return 720; }
                @Override public int height() { return timeMs == aspectChangeAt ? 720 : 1280; }
                @Override public void close() { closedImages.add(timeMs); }
            };
        }
        @Override public PoseFrame detect(ImageFrame<Long> frame, long timeMs)
                throws Exception {
            assertEquals((long) frame.image(), timeMs);
            detected.add(timeMs);
            if (cancelOnDetection) cancelled = true;
            if (timeMs == failAt) throw new IOException("fake detector failure");
            return poses.at(timeMs);
        }
        @Override public void close() {
            // 同一对象模拟两个资源；由打开检测器前后区分。
            if (!engineClosed && everyFrame != null) engineClosed = true;
            else sourceClosed = true;
        }
    }

    @Test public void freshVideoFramesFeedCurrentSmootherAndSynchronizedTimeline() throws Exception {
        Session saved = session(ExerciseType.SQUAT);
        FakeFactory fake = new FakeFactory();
        fake.poses = t -> shiftLandmarks(squat(t, 170f, 0f), t / 100f * .02f, 0f,
                PoseFrame.LANDMARK_SUBSET);
        final List<Integer> progress = new ArrayList<>();
        VideoPoseReanalysis result = VideoReanalysisRunner.run(fake, saved, null,
                (complete, total) -> { assertEquals(5, total); progress.add(complete); });
        assertEquals(Arrays.asList(0L, 100L, 200L, 300L, 400L), fake.requested);
        assertEquals(fake.requested, fake.detected);
        assertEquals(fake.requested, fake.closedImages);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5), progress);
        assertTrue(fake.sourceClosed && fake.engineClosed);
        assertEquals(Boolean.FALSE, fake.everyFrame);
        assertEquals(5, result.size());
        assertEquals(100, result.getIntervalMs());
        assertEquals(450, result.getDurationMs());
        assertEquals(720, result.getSourceWidth());
        assertEquals(1280, result.getSourceHeight());
        assertEquals(.47f, result.getRawFrame(1).getX(PoseFrame.LEFT_SHOULDER), .0001f);
        assertTrue("滤波值应来自视频帧而不是直接复制原始点",
                result.getFilteredFrame(1).getX(PoseFrame.LEFT_SHOULDER) < .47f);
        assertTrue(result.getFilteredFrame(1).getX(PoseFrame.LEFT_SHOULDER) > .45f);
        assertEquals(2, result.getTimeline().indexAt(299));
        assertEquals(3, result.getTimeline().indexAt(300));
        assertEquals(4, result.getTimeline().indexAt(999));

        PoseSmoother direct = new PoseSmoother();
        SquatCounter counter = new SquatCounter(null);
        FrameGapGuard guard = new FrameGapGuard();
        for (int i = 0; i < result.size(); i++) {
            PoseFrame raw = fake.poses.at(i * 100L);
            PoseFrame filtered = direct.smooth(raw.maskOutOfFrame(720, 1280));
            guard.process(counter, filtered);
            assertEquals(filtered.getX(PoseFrame.LEFT_SHOULDER),
                    result.getFilteredFrame(i).getX(PoseFrame.LEFT_SHOULDER), .00001f);
            assertEquals(counter.getGoodReps(), result.getTimeline().getGoodRepsAt(i));
            assertEquals(counter.getBadReps(), result.getTimeline().getBadRepsAt(i));
        }
        assertEquals("original.mp4", saved.getVideoFile());
        assertEquals("old-pose.dat", saved.getPoseFile());
        assertEquals(73, saved.getReps());
        assertEquals(4, saved.getBadReps());
        assertEquals(12345, saved.getDurationMs());
    }

    @Test public void missingFramesAreExplicitAndBoundsAffectOnlyFilteredPoints() throws Exception {
        FakeFactory fake = new FakeFactory();
        fake.missingAt = 100;
        fake.poses = t -> shiftLandmarks(squat(t, 170f, 0f), .65f, 0f,
                PoseFrame.LEFT_SHOULDER);
        VideoPoseReanalysis result = VideoReanalysisRunner.run(fake, session(ExerciseType.SQUAT), null, null);
        assertEquals(1, result.getMissingFrames());
        assertFalse(result.getRawFrame(1).isVisible());
        assertEquals(100, result.getRawFrame(1).getTimestampMs());
        assertEquals(Arrays.asList(0L, 200L, 300L, 400L), fake.detected);
        assertEquals(fake.detected, fake.closedImages);
        assertTrue(result.getRawFrame(0).getX(PoseFrame.LEFT_SHOULDER) > 1f);
        assertEquals(0f, result.getFilteredFrame(0).getConfidence(PoseFrame.LEFT_SHOULDER), .00001f);
    }

    @Test public void recordedCalibrationIndicesAreNotAppliedToResampledVideo() throws Exception {
        Session saved = session(ExerciseType.SQUAT);
        PersonalCalibration event = new PersonalCalibration(ExerciseType.SQUAT, "", .6f,
                130f, .2f, 55f, 0f, .08f, false, false);
        assertTrue(event.isValid());
        saved.addCalibrationChange(1, event);
        FakeFactory fake = new FakeFactory();
        fake.poses = PoseFrame::empty;
        VideoPoseReanalysis result = VideoReanalysisRunner.run(fake, saved, null, null);
        List<PoseFrame> oldFrames = Arrays.asList(PoseFrame.empty(0), PoseFrame.empty(100),
                PoseFrame.empty(200), PoseFrame.empty(300), PoseFrame.empty(400));
        ReplayTimeline original = ReplayTimeline.build(oldFrames, saved);
        assertEquals("录制骨架回放仍要保留原来的校准点击", 1, original.getGoodRepsAt(1));
        assertEquals("视频抽样下标不能套用旧骨架下标", 0, result.getTimeline().getGoodRepsAt(1));
        assertEquals(1, saved.getCalibrationChanges().size());
        assertEquals(73, saved.getReps());
    }

    @Test public void startingPersonalTargetIsUsedForVideoRecalculation() {
        Session saved = session(ExerciseType.PUSHUP);
        PersonalCalibration initial = new PersonalCalibration(ExerciseType.PUSHUP, "", .55f,
                115f, .2f, .13f, 150f, 0f, false, false);
        assertTrue(initial.isValid());
        saved.setInitialCalibration(initial);
        List<PoseFrame> frames = new ArrayList<>();
        long t = 0;
        for (float angle : repProfile(115f, 168f, 8)) {
            frames.add(pushUp(t, angle, 0f, false)); t += FPS_STEP_MS;
        }
        for (int i = 0; i < 3; i++) { frames.add(pushUp(t, 168f, 0f, false)); t += FPS_STEP_MS; }
        ReplayTimeline personalized = ReplayTimeline.buildForVideo(frames, saved);
        ReplayTimeline standard = ReplayTimeline.build(frames, ExerciseType.PUSHUP, "");
        int last = frames.size() - 1;
        assertEquals(1, personalized.getGoodRepsAt(last));
        assertEquals(0, standard.getGoodRepsAt(last));
        assertEquals(1, standard.getBadRepsAt(last));
        assertSame(initial, saved.getInitialCalibration());
    }

    @Test public void videoCanBeReanalyzedWithoutAnyRecordedSkeleton() throws Exception {
        Session saved = session(ExerciseType.SQUAT);
        saved.setPoseFile(null);
        FakeFactory fake = new FakeFactory();
        VideoPoseReanalysis result = VideoReanalysisRunner.run(fake, saved, null, null);
        assertEquals(5, result.size());
        assertNull(saved.getPoseFile());
        assertEquals(73, saved.getReps());
        assertEquals(5, fake.detected.size());
    }

    @Test public void choosesDetectorModeForEveryExercise() throws Exception {
        for (ExerciseType type : ExerciseType.values()) {
            FakeFactory fake = new FakeFactory();
            fake.duration = 1;
            VideoReanalysisRunner.run(fake, session(type), null, null);
            assertFalse("所有动作应使用连续跟踪: " + type, fake.everyFrame.booleanValue());
        }
    }

    @Test public void quarterTurnIsOnlyAppliedWhenEncodedAndDisplayedAspectsAreUnambiguous() {
        assertTrue(VideoFrameOrientation.needsQuarterTurn(1920, 1080, 90, 960, 540));
        assertTrue(VideoFrameOrientation.needsQuarterTurn(1920, 1080, 270, 960, 540));
        assertFalse("已经摆正的图不能再转一次",
                VideoFrameOrientation.needsQuarterTurn(1920, 1080, 90, 540, 960));
        assertFalse(VideoFrameOrientation.needsQuarterTurn(1000, 1000, 90, 500, 500));
        assertFalse(VideoFrameOrientation.needsQuarterTurn(1920, 1080, 180, 960, 540));
        assertFalse(VideoFrameOrientation.needsQuarterTurn(0, 1080, 90, 960, 540));
        assertFalse(VideoFrameOrientation.needsQuarterTurn(1920, 1080, 90, 800, 600));
        assertFalse(VideoFrameOrientation.needsQuarterTurn(1920, 1080, 90, 0, 540));
        assertTrue(VideoFrameOrientation.matchesUprightAspect(1920, 1080, 90, 540, 960));
        assertFalse("不应把被错误拉伸成方形的画面叠加到宽屏录像",
                VideoFrameOrientation.matchesUprightAspect(1920, 1080, 90, 960, 960));
        assertFalse(VideoFrameOrientation.matchesUprightAspect(1920, 1080, 90, 960, 540));
        assertTrue(VideoFrameOrientation.matchesUprightAspect(1920, 1080, 0, 960, 540));
        assertTrue("缺少元数据时不凭空猜测", VideoFrameOrientation.matchesUprightAspect(0, 0, 90, 960, 540));
    }

    @Test public void samplingLimitsAreBoundedWithoutOverflow() throws Exception {
        assertEquals(100, VideoReanalysisRunner.sampleIntervalMs(1));
        assertEquals(100, VideoReanalysisRunner.sampleIntervalMs(600000));
        assertEquals(101, VideoReanalysisRunner.sampleIntervalMs(600001));
        assertEquals(350, VideoReanalysisRunner.sampleIntervalMs(2100000));
        for (long invalid : new long[]{0, -1, 2100001, Long.MAX_VALUE}) {
            try { VideoReanalysisRunner.sampleIntervalMs(invalid); fail("无效时长 " + invalid); }
            catch (IOException expected) { /* expected */ }
        }
        FakeFactory fake = new FakeFactory();
        fake.duration = Long.MAX_VALUE;
        try { VideoReanalysisRunner.run(fake, session(ExerciseType.SQUAT), null, null); fail(); }
        catch (IOException expected) { assertTrue(fake.sourceClosed); assertNull(fake.everyFrame); }
    }

    @Test public void cancellationDuringDetectionClosesImageAndBothResources() throws Exception {
        FakeFactory fake = new FakeFactory();
        fake.cancelOnDetection = true;
        try {
            VideoReanalysisRunner.run(fake, session(ExerciseType.SQUAT), () -> fake.cancelled, null);
            fail("取消后不能发布结果");
        } catch (VideoReanalysisRunner.Cancelled expected) {
            assertEquals(Arrays.asList(0L), fake.requested);
            assertEquals(Arrays.asList(0L), fake.closedImages);
            assertTrue(fake.engineClosed && fake.sourceClosed);
        }
    }

    @Test public void decodingAndInferenceFailuresCloseResourcesWithoutPartialResult() throws Exception {
        FakeFactory detectionFailure = new FakeFactory();
        detectionFailure.failAt = 200;
        try { VideoReanalysisRunner.run(detectionFailure, session(ExerciseType.SQUAT), null, null); fail(); }
        catch (IOException expected) {
            assertEquals(Arrays.asList(0L, 100L, 200L), detectionFailure.closedImages);
            assertTrue(detectionFailure.engineClosed && detectionFailure.sourceClosed);
        }
        FakeFactory allMissing = new FakeFactory();
        allMissing.missingAll = true;
        try { VideoReanalysisRunner.run(allMissing, session(ExerciseType.SQUAT), null, null); fail(); }
        catch (IOException expected) {
            assertTrue(allMissing.detected.isEmpty());
            assertTrue(allMissing.engineClosed && allMissing.sourceClosed);
        }
    }

    @Test public void aspectRatioChangeCannotProduceMisalignedOverlay() throws Exception {
        FakeFactory fake = new FakeFactory();
        fake.aspectChangeAt = 200;
        try { VideoReanalysisRunner.run(fake, session(ExerciseType.SQUAT), null, null); fail(); }
        catch (IOException expected) {
            assertEquals(Arrays.asList(0L, 100L), fake.detected);
            assertEquals(Arrays.asList(0L, 100L, 200L), fake.closedImages);
            assertTrue(fake.engineClosed && fake.sourceClosed);
        }
    }

    /** 两个互相独立的检测器，覆盖真实流水线中重建候选/关闭资源的分支。 */
    private static class RecoveryFactory implements VideoReanalysisRunner.Factory<Long> {
        final List<Boolean> modes = new ArrayList<>();
        final List<Integer> closed = new ArrayList<>();
        final List<Long> closedImages = new ArrayList<>();
        boolean sourceClosed, candidateIncomplete, candidateThrows, cancelCandidate, cancelled, stationary;
        long duration = 2500;
        @Override public VideoReanalysisRunner.FrameSource<Long> openSource() {
            return new VideoReanalysisRunner.FrameSource<Long>() {
                @Override public long durationMs() { return duration; }
                @Override public ImageFrame<Long> frameAt(final long t) {
                    return new ImageFrame<Long>() {
                        @Override public Long image() { return t; }
                        @Override public int width() { return 720; }
                        @Override public int height() { return 1280; }
                        @Override public void close() { closedImages.add(t); }
                    };
                }
                @Override public void close() { sourceClosed = true; }
            };
        }
        @Override public VideoReanalysisRunner.PoseEngine<Long> openEngine(boolean every) {
            modes.add(every);
            final int index = modes.size() - 1;
            return new VideoReanalysisRunner.PoseEngine<Long>() {
                @Override public PoseFrame detect(ImageFrame<Long> image, long t) throws Exception {
                    PoseFrame raw = shiftLandmarks(withFullVisibleSkeleton(standing(t)), stationary ? 0f : .002f * (t / 100f),
                            0f, PoseFrame.LEFT_WRIST);
                    if (index == 0) return raw;
                    if (cancelCandidate) cancelled = true;
                    if (candidateThrows) throw new IOException("candidate failed");
                    PoseFrame fresh = shiftLandmarks(raw, .02f, 0f, PoseFrame.LEFT_SHOULDER,
                            PoseFrame.RIGHT_SHOULDER, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP);
                    return candidateIncomplete ? lowerConfidence(fresh, 0f, PoseFrame.LEFT_SHOULDER,
                            PoseFrame.RIGHT_SHOULDER) : fresh;
                }
                @Override public void close() { closed.add(index); }
            };
        }
    }

    @Test public void frozenVideoRetriesOnlyOnceAndAdoptsCompleteFreshStream() throws Exception {
        RecoveryFactory f = new RecoveryFactory();
        VideoPoseReanalysis result = VideoReanalysisRunner.run(f, session(ExerciseType.SQUAT), null, null);
        assertEquals(Arrays.asList(false, false), f.modes);
        assertEquals(Arrays.asList(0, 1), f.closed);
        assertEquals(25, f.closedImages.size());
        assertTrue(f.sourceClosed);
        assertEquals(.47f, result.getRawFrame(20).getX(PoseFrame.LEFT_SHOULDER), .0001f);
        assertEquals(.47f, result.getRawFrame(21).getX(PoseFrame.LEFT_SHOULDER), .0001f);
    }

    @Test public void rejectedOrFailedFreshStreamKeepsOriginalAndReleasesCandidate() throws Exception {
        for (boolean incomplete : new boolean[]{true, false}) {
            RecoveryFactory f = new RecoveryFactory();
            f.candidateIncomplete = incomplete;
            f.candidateThrows = !incomplete;
            VideoPoseReanalysis result = VideoReanalysisRunner.run(f, session(ExerciseType.SQUAT), null, null);
            assertEquals(Arrays.asList(false, false), f.modes);
            assertEquals(Arrays.asList(1, 0), f.closed);
            assertEquals(.45f, result.getRawFrame(20).getX(PoseFrame.LEFT_SHOULDER), .0001f);
            assertTrue(f.sourceClosed);
            assertEquals(25, f.closedImages.size());
        }
    }

    @Test public void noFrozenPatternOrOtherExerciseNeverOpensCandidate() throws Exception {
        RecoveryFactory still = new RecoveryFactory(); still.stationary = true;
        VideoReanalysisRunner.run(still, session(ExerciseType.SQUAT), null, null);
        assertEquals(Arrays.asList(false), still.modes);
        RecoveryFactory push = new RecoveryFactory();
        VideoReanalysisRunner.run(push, session(ExerciseType.PUSHUP), null, null);
        assertEquals(Arrays.asList(false), push.modes);
    }

    @Test public void cancellationDuringFreshDetectionReleasesBothStreamsAndImage() throws Exception {
        RecoveryFactory f = new RecoveryFactory(); f.cancelCandidate = true;
        try {
            VideoReanalysisRunner.run(f, session(ExerciseType.SQUAT), () -> f.cancelled, null);
            fail("取消不能发布部分结果");
        } catch (VideoReanalysisRunner.Cancelled expected) {
            assertEquals(Arrays.asList(1, 0), f.closed);
            assertEquals(21, f.closedImages.size());
            assertTrue(f.sourceClosed);
        }
    }
}
