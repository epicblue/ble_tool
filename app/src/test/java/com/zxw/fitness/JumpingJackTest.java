package com.zxw.fitness;

import com.zxw.fitness.analysis.AlgorithmVersion;
import com.zxw.fitness.analysis.FrameGapGuard;
import com.zxw.fitness.analysis.HistoryGrowth;
import com.zxw.fitness.analysis.JumpingJackCounter;
import com.zxw.fitness.analysis.RepEvent;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.ReplayTimeline.FrameState;
import com.zxw.fitness.analysis.VideoPoseReanalysis;
import com.zxw.fitness.analysis.VideoReanalysisRunner;
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
import com.zxw.fitness.pose.PoseSmoother;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** 合成几何帧测试状态机与录制协议；不能代表相机或真人识别准确率。 */
public class JumpingJackTest {
    private static final int[] REQUIRED = {PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER,
            PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP, PoseFrame.LEFT_WRIST, PoseFrame.RIGHT_WRIST,
            PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE};

    static PoseFrame jack(long t, float arms, float leftFoot, float rightFoot) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT], y = x.clone(), z = x.clone(), c = x.clone();
        x[PoseFrame.LEFT_SHOULDER] = .43f; y[PoseFrame.LEFT_SHOULDER] = .35f;
        x[PoseFrame.RIGHT_SHOULDER] = .57f; y[PoseFrame.RIGHT_SHOULDER] = .35f;
        x[PoseFrame.LEFT_HIP] = .46f; y[PoseFrame.LEFT_HIP] = .55f;
        x[PoseFrame.RIGHT_HIP] = .54f; y[PoseFrame.RIGHT_HIP] = .55f;
        double angle = Math.toRadians(arms * 165);
        float dx = (float) Math.sin(angle) * .22f, dy = (float) Math.cos(angle) * .22f;
        x[PoseFrame.LEFT_WRIST] = .43f - dx; y[PoseFrame.LEFT_WRIST] = .35f + dy;
        x[PoseFrame.RIGHT_WRIST] = .57f + dx; y[PoseFrame.RIGHT_WRIST] = .35f + dy;
        x[PoseFrame.LEFT_ANKLE] = .47f - .13f * leftFoot; y[PoseFrame.LEFT_ANKLE] = .95f;
        x[PoseFrame.RIGHT_ANKLE] = .53f + .13f * rightFoot; y[PoseFrame.RIGHT_ANKLE] = .95f;
        for (int id : REQUIRED) c[id] = .9f;
        // 这几个点用于骨架绘制，不参与开合跳测量。
        x[PoseFrame.NOSE] = .5f; y[PoseFrame.NOSE] = .22f; c[PoseFrame.NOSE] = .9f;
        for (int[] triple : new int[][]{{PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST},
                {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST},
                {PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE},
                {PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE}}) {
            x[triple[1]] = (x[triple[0]] + x[triple[2]]) / 2f;
            y[triple[1]] = (y[triple[0]] + y[triple[2]]) / 2f; c[triple[1]] = .9f;
        }
        return PoseFrame.of(t, x, y, z, c);
    }

    private static final class Sequence {
        long time = 1000;
        final List<PoseFrame> frames = new ArrayList<>();
        Sequence add(float arms, float feet, int n) { return add(arms, feet, feet, n); }
        Sequence add(float arms, float left, float right, int n) {
            for (int i = 0; i < n; i++, time += 100) frames.add(jack(time, arms, left, right));
            return this;
        }
        Sequence full() {
            return add(0, 0, 5).add(.25f, .25f, 2).add(.5f, .5f, 2).add(.75f, .75f, 2)
                    .add(1, 1, 3).add(.75f, .75f, 2).add(.5f, .5f, 2).add(.25f, .25f, 2).add(0, 0, 4);
        }
    }

    private static void feed(JumpingJackCounter counter, List<PoseFrame> frames) {
        FrameGapGuard guard = new FrameGapGuard();
        for (PoseFrame frame : frames) guard.process(counter, frame);
    }

    private static PoseFrame copy(PoseFrame frame, long t, int missing, float scale,
                                  float dx, float dy, boolean mirror) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT], y = x.clone(), z = x.clone(), c = x.clone();
        for (int i = 0; i < x.length; i++) {
            x[i] = (mirror ? -frame.getX(i) : frame.getX(i)) * scale + dx;
            y[i] = frame.getY(i) * scale + dy;
            c[i] = frame.getConfidence(i);
        }
        if (missing >= 0) c[missing] = 0;
        return PoseFrame.of(t, x, y, z, c);
    }

    @Test public void completeCyclesCountOnlyAfterReturningClosed() {
        Sequence seq = new Sequence(); seq.full().full();
        JumpingJackCounter counter = new JumpingJackCounter(null);
        int first = -1;
        for (int i = 0; i < seq.frames.size(); i++) {
            counter.process(seq.frames.get(i));
            if (counter.getGoodReps() == 1 && first < 0) first = i;
        }
        assertTrue("必须等到接近合拢才计数", first > 16);
        assertEquals(2, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        assertEquals("2 次", counter.getScoreText());
        assertTrue(counter.hasResult());
    }

    @Test public void startingOpenOrEndingOpenDoesNotInventARep() {
        Sequence seq = new Sequence().add(1, 1, 4).add(.5f, .5f, 2).add(0, 0, 5)
                .add(.5f, .5f, 2).add(1, 1, 4);
        JumpingJackCounter c = new JumpingJackCounter(null);
        feed(c, seq.frames);
        assertEquals(0, c.getGoodReps());
        assertEquals(0, c.getBadReps());
        long offset = seq.time - 1000;
        seq = new Sequence().add(.5f, .5f, 2).add(0, 0, 4);
        for (PoseFrame f : seq.frames) c.process(copy(f, f.getTimestampMs() + offset, -1, 1, 0, 0, false));
        assertEquals(1, c.getGoodReps());
    }

    @Test public void partialOpeningIsSeparateFromValidAndHasReason() {
        Sequence seq = new Sequence().add(0, 0, 5).add(.45f, .25f, 5).add(0, 0, 4);
        List<RepEvent> events = new ArrayList<>();
        JumpingJackCounter c = new JumpingJackCounter(events::add);
        feed(c, seq.frames);
        assertEquals(0, c.getGoodReps()); assertEquals(1, c.getBadReps());
        assertEquals(1, c.getFormErrors()); assertEquals(1, events.size());
        assertFalse(events.get(0).valid);
        assertEquals("双臂或双脚张开幅度不足", events.get(0).reason);
        assertTrue("完成后几帧仍显示原因", c.getStatusText().contains("张开幅度不足"));
    }

    @Test public void armsOnlyFeetOnlyAndSingleFootDoNotCount() {
        for (int mode = 0; mode < 3; mode++) {
            Sequence seq = new Sequence().add(0, 0, 5);
            if (mode == 0) seq.add(1, 0, 6);
            if (mode == 1) seq.add(0, 1, 6);
            if (mode == 2) seq.add(1, 0, 1, 6);
            seq.add(0, 0, 5);
            JumpingJackCounter c = new JumpingJackCounter(null); feed(c, seq.frames);
            assertEquals("mode " + mode, 0, c.getGoodReps());
            assertEquals("mode " + mode, 0, c.getBadReps());
        }
    }

    @Test public void aSingleStationaryFootIsNotMadeMovingByHipShift() {
        JumpingJackCounter c = new JumpingJackCounter(null); long t = 1000;
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        for (int i = 0; i < 5; i++, t += 100) {
            float[] v = jack(t, 1, 0, 1).toCompactArray();
            v[PoseFrame.subsetIndexOf(PoseFrame.LEFT_HIP) * 4] += .06f;
            v[PoseFrame.subsetIndexOf(PoseFrame.RIGHT_HIP) * 4] += .06f;
            c.process(PoseFrame.fromCompactArray(t, v));
        }
        assertTrue("单脚没有向外时进度条不能显示已达标", c.getDepthProgress() < .1f);
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        assertEquals("相对髋部的假外移不能当作双脚都移动", 0, c.getGoodReps());
        assertEquals(0, c.getBadReps());
    }

    @Test public void smallWholeBodySwayStillAllowsAFullJack() {
        Sequence seq = new Sequence(); seq.full();
        JumpingJackCounter c = new JumpingJackCounter(null);
        for (int i = 0; i < seq.frames.size(); i++) {
            PoseFrame f = seq.frames.get(i);
            c.process(copy(f, f.getTimestampMs(), -1, 1, (float) Math.sin(i * .25) * .008f, 0, false));
        }
        assertEquals(1, c.getGoodReps()); assertEquals(0, c.getBadReps());
    }

    @Test public void sequentialArmsAndFeetPeaksAreNeverCombined() {
        Sequence seq = new Sequence().add(0, 0, 5).add(1, 0, 5).add(0, 0, 3)
                .add(0, 1, 5).add(0, 0, 5);
        JumpingJackCounter c = new JumpingJackCounter(null); feed(c, seq.frames);
        assertEquals(0, c.getGoodReps()); assertEquals(0, c.getBadReps());
    }

    @Test public void oneRaisedArmWithBothFeetIsNotEnough() {
        Sequence seq = new Sequence().add(0, 0, 5).add(1, 1, 5).add(0, 0, 5);
        JumpingJackCounter c = new JumpingJackCounter(null);
        for (PoseFrame f : seq.frames) {
            float[] values = f.toCompactArray();
            int left = PoseFrame.subsetIndexOf(PoseFrame.LEFT_WRIST) * 4;
            PoseFrame resting = jack(f.getTimestampMs(), 0, 0, 0);
            values[left] = resting.getX(PoseFrame.LEFT_WRIST);
            values[left + 1] = resting.getY(PoseFrame.LEFT_WRIST);
            c.process(PoseFrame.fromCompactArray(f.getTimestampMs(), values));
        }
        assertEquals(0, c.getGoodReps()); assertEquals(0, c.getBadReps());
    }

    @Test public void bothWristsAboveShouldersAreNotEnoughIfOneArmIsNotRaised() {
        Sequence seq = new Sequence().add(0, 0, 5).add(1, 1, 5).add(0, 0, 5);
        JumpingJackCounter c = new JumpingJackCounter(null);
        for (int i = 0; i < seq.frames.size(); i++) {
            PoseFrame f = seq.frames.get(i);
            if (i >= 5 && i < 10) {
                float[] v = f.toCompactArray();
                int l = PoseFrame.subsetIndexOf(PoseFrame.LEFT_WRIST) * 4;
                PoseFrame shoulderHeight = jack(f.getTimestampMs(), .6f, 1, 1);
                v[l] = shoulderHeight.getX(PoseFrame.LEFT_WRIST);
                v[l + 1] = shoulderHeight.getY(PoseFrame.LEFT_WRIST);
                f = PoseFrame.fromCompactArray(f.getTimestampMs(), v);
            }
            c.process(f);
        }
        assertEquals(0, c.getGoodReps()); assertEquals(1, c.getBadReps());
    }

    @Test public void corruptedConfidenceAboveOneIsNotTrustedOnEitherSide() {
        Sequence seq = new Sequence(); seq.full();
        for (int id : REQUIRED) {
            JumpingJackCounter c = new JumpingJackCounter(null);
            for (PoseFrame f : seq.frames) {
                float[] v = f.toCompactArray(); v[PoseFrame.subsetIndexOf(id) * 4 + 3] = 1.1f;
                c.process(PoseFrame.fromCompactArray(f.getTimestampMs(), v));
            }
            assertEquals("corrupt " + id, 0, c.getGoodReps());
            assertEquals("corrupt " + id, 0, c.getBadReps());
        }
    }

    @Test public void eachRequiredJointMustBeReliable() {
        Sequence seq = new Sequence(); seq.full();
        for (int missing : REQUIRED) {
            JumpingJackCounter c = new JumpingJackCounter(null);
            for (PoseFrame f : seq.frames) c.process(copy(f, f.getTimestampMs(), missing, 1, 0, 0, false));
            assertEquals("missing " + missing, 0, c.getGoodReps());
            assertEquals("missing " + missing, 0, c.getBadReps());
            assertTrue(c.getStatusText().contains("不完整"));
        }
    }

    private static PoseFrame leaningJack(long time, boolean open) {
        PoseFrame base = jack(time, 0, open ? 1 : 0, open ? 1 : 0);
        float[] v = base.toCompactArray();
        int ls = PoseFrame.subsetIndexOf(PoseFrame.LEFT_SHOULDER) * 4;
        int rs = PoseFrame.subsetIndexOf(PoseFrame.RIGHT_SHOULDER) * 4;
        int lh = PoseFrame.subsetIndexOf(PoseFrame.LEFT_HIP) * 4;
        int rh = PoseFrame.subsetIndexOf(PoseFrame.RIGHT_HIP) * 4;
        int lw = PoseFrame.subsetIndexOf(PoseFrame.LEFT_WRIST) * 4;
        int rw = PoseFrame.subsetIndexOf(PoseFrame.RIGHT_WRIST) * 4;
        int la = PoseFrame.subsetIndexOf(PoseFrame.LEFT_ANKLE) * 4;
        int ra = PoseFrame.subsetIndexOf(PoseFrame.RIGHT_ANKLE) * 4;
        v[lh] = .63f; v[lh + 1] = .45f; v[rh] = .71f; v[rh + 1] = .45f;
        for (int[] side : new int[][]{{ls, lh, lw}, {rs, rh, rw}}) {
            float dx = v[side[1]] - v[side[0]], dy = v[side[1] + 1] - v[side[0] + 1];
            float n = (float) Math.hypot(dx, dy);
            v[side[2]] = v[side[0]] + (open ? -.219f : .22f * dx / n);
            v[side[2] + 1] = v[side[0] + 1] + (open ? .019f : .22f * dy / n);
        }
        v[la] += .17f; v[ra] += .17f;
        return PoseFrame.fromCompactArray(time, v);
    }

    @Test public void leaningCannotMakeHandsBelowShoulderAStandardOverheadPose() {
        JumpingJackCounter c = new JumpingJackCounter(null); long t = 1000;
        for (int i = 0; i < 5; i++, t += 100) c.process(leaningJack(t, false));
        for (int i = 0; i < 5; i++, t += 100) c.process(leaningJack(t, true));
        for (int i = 0; i < 5; i++, t += 100) c.process(leaningJack(t, false));
        assertEquals("二维角度虽大但手腕没有高过肩", 0, c.getGoodReps());
        assertEquals(1, c.getBadReps());
    }

    @Test public void sideViewCannotStartOrCompleteAJack() {
        Sequence seq = new Sequence(); seq.full();
        JumpingJackCounter c = new JumpingJackCounter(null);
        for (PoseFrame f : seq.frames) {
            float[] v = f.toCompactArray();
            v[PoseFrame.subsetIndexOf(PoseFrame.LEFT_SHOULDER) * 4] = .49f;
            v[PoseFrame.subsetIndexOf(PoseFrame.RIGHT_SHOULDER) * 4] = .51f;
            c.process(PoseFrame.fromCompactArray(f.getTimestampMs(), v));
        }
        assertEquals(0, c.getGoodReps()); assertEquals(0, c.getBadReps());
        assertTrue(c.getStatusText().contains("调整站姿"));
    }

    @Test public void scaleTranslationAndMirrorLeaveCountsUnchanged() {
        Sequence seq = new Sequence(); seq.full();
        for (float scale : new float[]{.6f, 1f, 1.5f}) for (boolean mirror : new boolean[]{false, true}) {
            JumpingJackCounter c = new JumpingJackCounter(null);
            for (PoseFrame f : seq.frames) c.process(copy(f, f.getTimestampMs(), -1, scale, 2, 1, mirror));
            assertEquals("scale=" + scale + " mirror=" + mirror, 1, c.getGoodReps());
            assertEquals(0, c.getBadReps());
        }
    }

    @Test public void bodyTranslationAsWellAsVerticalBounceDoNotMimicOpeningFeet() {
        Sequence seq = new Sequence().add(0, 0, 5).add(1, 0, 6).add(0, 0, 5);
        JumpingJackCounter c = new JumpingJackCounter(null);
        for (int i = 0; i < seq.frames.size(); i++) {
            PoseFrame f = seq.frames.get(i);
            c.process(copy(f, f.getTimestampMs(), -1, 1, i * .01f, (i % 3) * .03f, false));
        }
        assertEquals(0, c.getGoodReps()); assertEquals(0, c.getBadReps());
    }

    @Test public void shortOcclusionCanContinueButLongGapAndTimeRollbackCancelCycle() {
        Sequence seq = new Sequence().add(0, 0, 5).add(1, 1, 3);
        JumpingJackCounter shortDrop = new JumpingJackCounter(null);
        FrameGapGuard guard = new FrameGapGuard();
        for (PoseFrame f : seq.frames) guard.process(shortDrop, f);
        guard.process(shortDrop, PoseFrame.empty(seq.time));
        for (int i = 1; i <= 4; i++) guard.process(shortDrop, jack(seq.time + i * 100, 0, 0, 0));
        assertEquals(1, shortDrop.getGoodReps());

        for (boolean rollback : new boolean[]{false, true}) {
            JumpingJackCounter c = new JumpingJackCounter(null); guard = new FrameGapGuard();
            for (PoseFrame f : seq.frames) guard.process(c, f);
            long after = rollback ? 500 : seq.time + 700;
            for (int i = 0; i < 5; i++) guard.process(c, jack(after + 100 * i, 0, 0, 0));
            assertEquals("rollback=" + rollback, 0, c.getGoodReps());
            assertEquals(0, c.getBadReps());
        }
    }

    @Test public void aVeryFastCycleIsIgnored() {
        JumpingJackCounter c = new JumpingJackCounter(null);
        for (int i = 0; i < 5; i++) c.process(jack(1000 + i * 100, 0, 0, 0));
        c.process(jack(1500, 1, 1, 1)); c.process(jack(1600, 0, 0, 0));
        assertEquals(0, c.getGoodReps()); assertEquals(0, c.getBadReps());
    }

    private static PersonalCalibration capture(JumpingJackCounter c, Sequence seq) {
        seq.add(0, 0, 5).add(.40f, .30f, 4);
        for (PoseFrame f : seq.frames) c.process(f);
        PersonalCalibration p = c.capturePersonalCalibration(seq.frames.get(seq.frames.size() - 1));
        assertNotNull(c.getCalibrationError(), p);
        assertTrue(p.isValid());
        return p;
    }

    @Test public void personalTargetCountsImmediatelyThenOnlyAfterFullReturn() {
        Sequence seq = new Sequence();
        JumpingJackCounter c = new JumpingJackCounter(null);
        PersonalCalibration p = capture(c, seq);
        assertTrue(p.hasFormWarning());
        assertTrue(p.describe().contains("双臂"));
        c.applyCalibrationEvent(p);
        assertEquals(1, c.getGoodReps());
        assertNull(c.capturePersonalCalibration(seq.frames.get(seq.frames.size() - 1)));
        c.applyCalibrationEvent(p); // 重复事件不重复加数
        assertEquals(1, c.getGoodReps());
        for (int i = 0; i < 5; i++) c.process(jack(seq.time + i * 100, .4f, .3f, .3f));
        assertEquals(1, c.getGoodReps());
        long t = seq.time + 500;
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, .4f, .3f, .3f));
        for (int i = 0; i < 4; i++, t += 100) c.process(jack(t, 0, 0, 0));
        assertEquals(2, c.getGoodReps()); assertEquals(0, c.getBadReps());
        assertEquals(2, c.getFormErrors());
        assertTrue(c.getStatusText().contains("按个人目标计数"));
    }

    @Test public void calibrationRequiresBothMovingStableAndRawData() {
        JumpingJackCounter c = new JumpingJackCounter(null);
        assertNull(c.capturePersonalCalibration(jack(0, 1, 1, 1)));
        Sequence seq = new Sequence().add(0, 0, 5).add(1, 0, 0, 4);
        for (PoseFrame f : seq.frames) c.process(f);
        assertNull(c.capturePersonalCalibration(seq.frames.get(seq.frames.size() - 1)));
        for (int i = 0; i < 4; i++) c.process(jack(seq.time + 100 * i, .4f, .3f, .3f));
        PoseFrame last = jack(seq.time + 300, .4f, .3f, .3f);
        assertNull(c.capturePersonalCalibration(copy(last, last.getTimestampMs(), PoseFrame.RIGHT_WRIST, 1, 0, 0, false)));
        // 原始点仍未打开一侧脚，滤波结果不能替代原始证据。
        assertNull(c.capturePersonalCalibration(jack(last.getTimestampMs(), .4f, .3f, 0)));
        assertNotNull(c.capturePersonalCalibration(last));
    }

    @Test public void calibrationRejectsARawOneFootStepEvenWithSimilarOverallWidth() {
        JumpingJackCounter c = new JumpingJackCounter(null); long t = 1000;
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        PoseFrame stable = null;
        for (int i = 0; i < 5; i++, t += 100) { stable = jack(t, .4f, .1f, .1f); c.process(stable); }
        assertNotNull(c.capturePersonalCalibration(stable));
        // 每脚差异均小于 0.15，且总脚距仍大于 0.12；原始右脚没有向外移动。
        assertNull(c.capturePersonalCalibration(jack(t - 100, .4f, .2f, 0)));
        // 两脚在画面都向外，但髋部右移使右脚相对髋部几乎未打开。
        float[] shifted = jack(t - 100, .4f, .2f, .1f).toCompactArray();
        shifted[PoseFrame.subsetIndexOf(PoseFrame.LEFT_HIP) * 4] += .01f;
        shifted[PoseFrame.subsetIndexOf(PoseFrame.RIGHT_HIP) * 4] += .01f;
        assertNull(c.capturePersonalCalibration(PoseFrame.fromCompactArray(t - 100, shifted)));
    }

    @Test public void calibrationRejectsMotionOfTheHigherArmToo() {
        JumpingJackCounter c = new JumpingJackCounter(null); long t = 1000;
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        PoseFrame last = null;
        for (float rightArm : new float[]{.4f, .8f, .4f}) {
            PoseFrame base = jack(t, .4f, .3f, .3f), other = jack(t, rightArm, .3f, .3f);
            float[] v = base.toCompactArray(); int r = PoseFrame.subsetIndexOf(PoseFrame.RIGHT_WRIST) * 4;
            v[r] = other.getX(PoseFrame.RIGHT_WRIST); v[r + 1] = other.getY(PoseFrame.RIGHT_WRIST);
            last = PoseFrame.fromCompactArray(t, v); c.process(last); t += 100;
        }
        assertNull(c.capturePersonalCalibration(last));
    }

    @Test public void unstableOrNonFiniteCalibrationSamplesAreRejected() {
        JumpingJackCounter c = new JumpingJackCounter(null);
        Sequence s = new Sequence().add(0, 0, 5).add(.4f, .3f, 2).add(.8f, .3f, 1);
        for (PoseFrame f : s.frames) c.process(f);
        assertNull(c.capturePersonalCalibration(s.frames.get(s.frames.size() - 1)));
        float[] v = jack(s.time, .4f, .3f, .3f).toCompactArray();
        v[4 * PoseFrame.subsetIndexOf(PoseFrame.LEFT_WRIST)] = Float.NaN;
        c.process(PoseFrame.fromCompactArray(s.time, v));
        assertNull(c.capturePersonalCalibration(PoseFrame.fromCompactArray(s.time, v)));
    }

    @Test public void asymmetricPersonalFootTargetAllowsSmallSamplingVariation() {
        JumpingJackCounter c = new JumpingJackCounter(null); long t = 1000;
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        PoseFrame sample = null;
        for (int i = 0; i < 5; i++, t += 100) { sample = jack(t, .4f, .11f, .62f); c.process(sample); }
        PersonalCalibration p = c.capturePersonalCalibration(sample);
        assertNotNull(c.getCalibrationError(), p); c.applyCalibrationEvent(p);
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, .4f, .105f, .62f));
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        assertEquals("保持各侧意义的同时为采样误差留余量", 2, c.getGoodReps());
    }

    @Test public void personalCalibrationDoesNotAcceptArmsOnlyOrOneFootOnLaterReps() {
        JumpingJackCounter c = new JumpingJackCounter(null);
        Sequence seq = new Sequence(); PersonalCalibration p = capture(c, seq); c.applyCalibrationEvent(p);
        long t = seq.time;
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, .4f, 0, .3f));
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        assertEquals(1, c.getGoodReps()); assertEquals(0, c.getBadReps());
    }

    @Test public void calibrationWaitSurvivesLongDropoutAndDoesNotRearmPartway() {
        JumpingJackCounter c = new JumpingJackCounter(null); Sequence s = new Sequence();
        PersonalCalibration p = capture(c, s); c.applyCalibrationEvent(p);
        c.process(PoseFrame.empty(s.time + 800));
        long t = s.time + 900;
        for (int i = 0; i < 4; i++, t += 100) c.process(jack(t, 0, .20f, .20f));
        for (int i = 0; i < 4; i++, t += 100) c.process(jack(t, .4f, .3f, .3f));
        assertEquals(1, c.getGoodReps());
        assertNull(c.capturePersonalCalibration(jack(t, .4f, .3f, .3f)));
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, .4f, .3f, .3f));
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        assertEquals(2, c.getGoodReps());
    }

    @Test public void invalidCalibrationDoesNotWeakenDefault() {
        PersonalCalibration invalid = new PersonalCalibration(ExerciseType.JUMPING_JACK, "", .33f,
                60f, .12f, 0, 0, .01f, false, true);
        assertFalse(invalid.isValid());
        JumpingJackCounter c = new JumpingJackCounter(null);
        c.setPersonalCalibration(invalid); c.applyCalibrationEvent(invalid);
        Sequence s = new Sequence().add(0, 0, 5).add(.45f, .25f, 5).add(0, 0, 5);
        feed(c, s.frames);
        assertEquals(0, c.getGoodReps()); assertEquals(1, c.getBadReps());
        assertFalse(new PersonalCalibration(ExerciseType.JUMPING_JACK, "", .9f,
                60f, .2f, 0, 0, .1f, false, true).isValid());
    }

    private static JumpingJackCounter cadence(int periodMs, int frameMs, int cycles) {
        PoseSmoother smoother = new PoseSmoother(ExerciseType.JUMPING_JACK);
        JumpingJackCounter counter = new JumpingJackCounter(null);
        FrameGapGuard guard = new FrameGapGuard(); long t = 1000;
        for (int i = 0; i < 6; i++, t += frameMs) guard.process(counter, smoother.smooth(jack(t, 0, 0, 0)));
        for (int elapsed = 0; elapsed < periodMs * cycles; elapsed += frameMs, t += frameMs) {
            float p = (float) ((1 - Math.cos(2 * Math.PI * elapsed / periodMs)) / 2);
            guard.process(counter, smoother.smooth(jack(t, p, p, p)));
        }
        for (int i = 0; i < 10; i++, t += frameMs) guard.process(counter, smoother.smooth(jack(t, 0, 0, 0)));
        return counter;
    }

    @Test public void smoothedSyntheticCyclesWorkAtSeveralFrameRatesAndPeriods() {
        for (int frame : new int[]{33, 67, 100}) for (int period : new int[]{800, 1200}) {
            JumpingJackCounter c = cadence(period, frame, 3);
            assertEquals("frame=" + frame + " period=" + period, 3, c.getGoodReps());
            assertEquals(0, c.getBadReps());
        }
        assertEquals(3, cadence(600, 33, 3).getGoodReps());
        JumpingJackCounter sparseFast = cadence(600, 100, 3);
        // 低帧率快动作仍可能漏计；不把这个合成案例写成真人准确率保证。
        assertTrue(sparseFast.getGoodReps() + sparseFast.getBadReps() <= 3);
    }

    @Test public void aSingleSimultaneousOutlierCannotFabricateACompletedCycle() {
        PoseSmoother smoother = new PoseSmoother(ExerciseType.JUMPING_JACK);
        JumpingJackCounter c = new JumpingJackCounter(null); long t = 1000;
        for (int i = 0; i < 6; i++, t += 100) c.process(smoother.smooth(jack(t, 0, 0, 0)));
        c.process(smoother.smooth(jack(t, 1, 1, 1))); t += 100;
        for (int i = 0; i < 8; i++, t += 100) c.process(smoother.smooth(jack(t, 0, 0, 0)));
        assertEquals(0, c.getGoodReps()); assertEquals(0, c.getBadReps());
    }

    @Test public void jackFilterAllowsModerateFastLimbsButRejectsOutliersAndLeavesOthersUnchanged() {
        PoseFrame start = jack(1000, 0, 0, 0), moved = jack(1100, .25f, .25f, .25f);
        PoseSmoother normal = new PoseSmoother();
        PoseSmoother oldAction = new PoseSmoother(ExerciseType.SQUAT);
        PoseSmoother jackFilter = new PoseSmoother(ExerciseType.JUMPING_JACK);
        for (PoseSmoother filter : new PoseSmoother[]{normal, oldAction, jackFilter}) filter.smooth(start);
        PoseFrame n = normal.smooth(moved), old = oldAction.smooth(moved), fast = jackFilter.smooth(moved);
        assertEquals(n.getX(PoseFrame.LEFT_WRIST), old.getX(PoseFrame.LEFT_WRIST), .000001f);
        assertEquals(n.getConfidence(PoseFrame.LEFT_WRIST), old.getConfidence(PoseFrame.LEFT_WRIST), .000001f);
        assertTrue(normal.getPendingJumps()[PoseFrame.LEFT_WRIST]);
        assertFalse(jackFilter.getPendingJumps()[PoseFrame.LEFT_WRIST]);
        assertNotEquals(n.getX(PoseFrame.LEFT_WRIST), fast.getX(PoseFrame.LEFT_WRIST), .001f);
        float[] corrupted = moved.toCompactArray();
        int wrist = PoseFrame.subsetIndexOf(PoseFrame.LEFT_WRIST) * 4;
        corrupted[wrist] += 1f;
        jackFilter.smooth(PoseFrame.fromCompactArray(1200, corrupted));
        assertTrue("极大突跳仍需稳定重捕获", jackFilter.getPendingJumps()[PoseFrame.LEFT_WRIST]);
    }

    @Test public void smallestCalibratedMovementCanStartAndMustReturnToItsOwnBaseline() {
        JumpingJackCounter c = new JumpingJackCounter(null); long t = 1000;
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        PoseFrame sample = null;
        for (int i = 0; i < 5; i++, t += 100) { sample = jack(t, .285f, .10f, .10f); c.process(sample); }
        PersonalCalibration p = c.capturePersonalCalibration(sample);
        assertNotNull(c.getCalibrationError(), p); assertTrue(p.isValid());
        c.applyCalibrationEvent(p);
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, .28f, .09f, .09f));
        // 手臂放下但双脚尚未回到基准附近，不能提前结算。
        for (int i = 0; i < 3; i++, t += 100) c.process(jack(t, 0, .09f, .09f));
        assertEquals(1, c.getGoodReps());
        for (int i = 0; i < 5; i++, t += 100) c.process(jack(t, 0, 0, 0));
        assertEquals(2, c.getGoodReps());
    }

    @Test public void diagnosisRequiresBothSidesAndWarnsAboutSideView() {
        PoseFrame f = jack(1000, 0, 0, 0);
        assertEquals(PoseSceneResult.Quality.USABLE,
                PoseSceneDiagnostics.inspect(f, ExerciseType.JUMPING_JACK, 720, 1280, false).quality);
        for (int missing : REQUIRED) {
            PoseSceneResult result = PoseSceneDiagnostics.inspect(copy(f, 1000, missing, 1, 0, 0, false),
                    ExerciseType.JUMPING_JACK, 720, 1280, false);
            assertEquals("missing " + missing, PoseSceneResult.Quality.WARNING, result.quality);
            assertTrue(result.message.contains("双"));
        }
        float[] v = f.toCompactArray();
        int shoulder = PoseFrame.subsetIndexOf(PoseFrame.RIGHT_SHOULDER) * 4;
        v[shoulder] = f.getX(PoseFrame.LEFT_SHOULDER) + .01f;
        PoseFrame side = PoseFrame.fromCompactArray(1000, v);
        PoseSceneResult result = PoseSceneDiagnostics.inspect(side, ExerciseType.JUMPING_JACK, 720, 1280, false);
        assertEquals(PoseSceneResult.Quality.WARNING, result.quality);
        assertTrue(result.message.contains("正对"));
        PoseFrame tiny = copy(f, 1000, -1, .1f, .4f, .4f, false);
        result = PoseSceneDiagnostics.inspect(tiny, ExerciseType.JUMPING_JACK, 720, 1280, false);
        assertEquals(PoseSceneResult.Quality.WARNING, result.quality);
        assertTrue(result.message.contains("较小"));
    }

    @Test public void catalogHistoryProfilesAndVersionsKeepActionsSeparate() {
        assertEquals(ExerciseType.JUMPING_JACK, ExerciseType.fromKey("jumping_jack"));
        assertTrue(Arrays.asList(ExerciseType.selectableValues()).contains(ExerciseType.JUMPING_JACK));
        assertTrue(ExerciseType.JUMPING_JACK.supportsPersonalCalibration());
        assertFalse(ExerciseType.sameCatalog("jumping_jack", "running"));
        assertEquals(AlgorithmVersion.JUMPING_JACK_CURRENT, AlgorithmVersion.forExercise(ExerciseType.JUMPING_JACK));
        assertEquals("fitness-2026-10-stream1", AlgorithmVersion.forExercise(ExerciseType.SQUAT));
        assertEquals("fitness-2026-10-pike1", AlgorithmVersion.forExercise(ExerciseType.PIKE_PUSHUP));
        Session jack = new Session(); jack.setExerciseType(ExerciseType.JUMPING_JACK); jack.setReps(3);
        Session run = new Session(); run.setExerciseType(ExerciseType.RUNNING);
        assertEquals(3f, jack.getResultValue(), 0);
        assertEquals(2, HistoryGrowth.from(Arrays.asList(jack, run), 1000, TimeZone.getTimeZone("UTC")).getExerciseTypes());
        UserProfile child = new UserProfile("1", "child", 0, "🌟");
        Sequence s = new Sequence(); PersonalCalibration p = capture(new JumpingJackCounter(null), s);
        child.setCalibration(p);
        assertSame(p, child.getCalibration(ExerciseType.JUMPING_JACK, ""));
        assertNull(child.getCalibration(ExerciseType.SQUAT, ""));
    }

    @Test public void skeletonAndVideoRecomputeRespectCalibrationEventSemantics() {
        Sequence s = new Sequence().add(0, 0, 5).add(.4f, .3f, 4).add(0, 0, 5)
                .add(.4f, .3f, 5).add(0, 0, 5);
        JumpingJackCounter direct = new JumpingJackCounter(null);
        List<Integer> expected = new ArrayList<>();
        Session session = new Session(); session.setExerciseType(ExerciseType.JUMPING_JACK);
        for (int i = 0; i < s.frames.size(); i++) {
            PoseFrame frame = s.frames.get(i); direct.process(frame);
            if (i == 8) {
                PersonalCalibration p = direct.capturePersonalCalibration(frame);
                assertNotNull(p); session.addCalibrationChange(i, p); direct.applyCalibrationEvent(p);
            }
            expected.add(direct.getGoodReps());
        }
        ReplayTimeline skeleton = ReplayTimeline.build(s.frames, session);
        ReplayTimeline video = ReplayTimeline.buildForVideo(s.frames, session);
        for (int i = 0; i < s.frames.size(); i++) assertEquals("frame " + i,
                (int) expected.get(i), skeleton.getGoodRepsAt(i));
        assertEquals(2, skeleton.getGoodRepsAt(skeleton.size() - 1));
        assertEquals("video 不能凭骨架帧号重放组中点击", 0, video.getGoodRepsAt(video.size() - 1));
        session.setInitialCalibration(session.getCalibrationChanges().get(0).calibration);
        video = ReplayTimeline.buildForVideo(s.frames, session);
        assertEquals("仅用初始目标重算", 2, video.getGoodRepsAt(video.size() - 1));
    }

    @Test public void binaryPoseAndVersionedTimelineRestoreEachCalibrationFrame() throws Exception {
        File dir = Files.createTempDirectory("jack-cache").toFile();
        File pose = new File(dir, "pose.dat"), cache = new File(dir, "timeline.dat");
        try {
            Sequence s = new Sequence().add(0, 0, 5).add(.4f, .3f, 4).add(0, 0, 5)
                    .add(.4f, .3f, 5).add(0, 0, 5);
            Session session = new Session(); session.setId(42); session.setUserId("child");
            session.setExerciseType(ExerciseType.JUMPING_JACK);
            session.setAlgorithmVersion(AlgorithmVersion.forExercise(ExerciseType.JUMPING_JACK));
            PoseLogWriter pw = new PoseLogWriter(pose, 720, 1280);
            Writer tw = new Writer(cache, session);
            JumpingJackCounter c = new JumpingJackCounter(null);
            List<FrameState> states = new ArrayList<>();
            for (int i = 0; i < s.frames.size(); i++) {
                PoseFrame f = s.frames.get(i); pw.addFrame(f, 0); c.process(f);
                FrameState state = ReplayTimeline.capture(ExerciseType.JUMPING_JACK, c, null);
                tw.addFrame(f, state); states.add(state);
                if (i == 8) {
                    PersonalCalibration p = c.capturePersonalCalibration(f); assertNotNull(p);
                    session.addCalibrationChange(i, p); c.applyCalibrationEvent(p);
                    state = ReplayTimeline.capture(ExerciseType.JUMPING_JACK, c, null);
                    tw.addCalibration(state); states.set(i, state);
                }
            }
            pw.close(); session.setReps(c.getGoodReps()); session.setBadReps(c.getBadReps());
            assertTrue(tw.finish(pose, session, s.frames.size()));
            PoseLog log = PoseLog.load(pose); assertNotNull(log);
            ReplayTimeline cached = RecordedTimeline.load(cache, pose, log, session);
            ReplayTimeline recomputed = ReplayTimeline.build(log.getFrames(), session);
            assertNotNull(cached); assertEquals(s.frames.size(), cached.size());
            for (int i = 0; i < cached.size(); i++) {
                assertEquals("cache " + i, states.get(i).good, cached.getGoodRepsAt(i));
                assertEquals("recompute " + i, states.get(i).good, recomputed.getGoodRepsAt(i));
                assertEquals(states.get(i).status, cached.getStatusAt(i));
            }
            session.setAlgorithmVersion(AlgorithmVersion.CURRENT);
            assertNull(RecordedTimeline.load(cache, pose, log, session));
        } finally { pose.delete(); cache.delete(); dir.delete(); }
    }

    private static final class FakeVideo implements VideoReanalysisRunner.Factory<Long>,
            VideoReanalysisRunner.FrameSource<Long>, VideoReanalysisRunner.PoseEngine<Long> {
        final List<PoseFrame> frames;
        int imagesClosed; boolean sourceClosed, engineClosed;
        FakeVideo(List<PoseFrame> frames) { this.frames = frames; }
        @Override public VideoReanalysisRunner.FrameSource<Long> openSource() { return this; }
        @Override public VideoReanalysisRunner.PoseEngine<Long> openEngine(boolean everyFrame) { return this; }
        @Override public long durationMs() { return frames.size() * 100L; }
        @Override public VideoReanalysisRunner.ImageFrame<Long> frameAt(final long t) {
            return new VideoReanalysisRunner.ImageFrame<Long>() {
                public Long image() { return t; }
                public int width() { return 720; }
                public int height() { return 1280; }
                public void close() { imagesClosed++; }
            };
        }
        @Override public PoseFrame detect(VideoReanalysisRunner.ImageFrame<Long> frame, long t) {
            PoseFrame src = frames.get((int) (t / 100));
            return copy(src, t, -1, 1, 0, 0, false);
        }
        @Override public void close() { if (!engineClosed) engineClosed = true; else sourceClosed = true; }
    }

    @Test public void syntheticVideoPipelineBuildsJackTimelineWithoutChangingHistory() throws Exception {
        Sequence seq = new Sequence(); seq.full();
        FakeVideo fake = new FakeVideo(seq.frames);
        Session session = new Session(); session.setExerciseType(ExerciseType.JUMPING_JACK);
        session.setVideoFile("original.mp4"); session.setPoseFile("original.dat"); session.setReps(42);
        VideoPoseReanalysis result = VideoReanalysisRunner.run(fake, session, null, null);
        assertEquals(seq.frames.size(), result.size());
        assertEquals(seq.frames.size(), fake.imagesClosed);
        assertTrue(fake.sourceClosed && fake.engineClosed);
        assertEquals(1, result.getTimeline().getGoodRepsAt(result.size() - 1));
        assertEquals(42, session.getReps()); assertEquals("original.mp4", session.getVideoFile());
        assertEquals("original.dat", session.getPoseFile());
    }
}
