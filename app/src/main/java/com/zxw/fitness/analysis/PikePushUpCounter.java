package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.PoseMath;

import java.util.Locale;

/**
 * 倒 V 折刀俯卧撑：保持髋高，屈肘下放后回到撑起位。
 * 只依据二维关键点估计次数，不检测地面、实际接触、疼痛或动作安全性。
 */
public final class PikePushUpCounter implements PersonalCalibrationAnalyzer {
    private static final float TOP_ANGLE = 150f;
    private static final float FULL_ANGLE = 100f;
    private static final float PARTIAL_ANGLE = 125f;
    private static final float MAX_STANDARD_HIP = 145f;
    private static final float MAX_TRACKED_HIP = 165f;
    private static final float MIN_SHOULDER_DROP = 0.10f; // 相对躯干长度
    private static final long MIN_REP_MS = 350;
    private static final long DROPOUT_MS = 500;
    private static final int SAMPLE_COUNT = 3;

    private static final int[] LEFT = {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW,
            PoseFrame.LEFT_WRIST, PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE};
    private static final int[] RIGHT = {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW,
            PoseFrame.RIGHT_WRIST, PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};

    private final RepListener listener;
    private final StableExtremum topAngleSamples = new StableExtremum(true);
    private final StableExtremum topReachSamples = new StableExtremum(true);
    private final float[] sampleElbows = new float[SAMPLE_COUNT];
    private final float[] sampleReaches = new float[SAMPLE_COUNT];
    private final float[] sampleHips = new float[SAMPLE_COUNT];
    private PersonalCalibration personal;
    private int samples, nextSample, topFrames;
    private long lastValidMs = -1, downStartMs;
    private float observedTopAngle = Float.NaN, observedTopReach = Float.NaN;
    private float baselineReach = Float.NaN, displayedDepth = Float.NaN;
    private float repMinElbow = Float.NaN, repMaxHip, repMaxDrop;
    private float armedMaxHip = Float.NaN;
    private boolean armed, downPhase, awaitingTop;
    private Measurement sample;
    private int goodReps, badReps, formErrors;
    private String phase = "等待开始", hint = "";
    private String calibrationError = "请先撑起，再在当前最低位置保持片刻";

    public PikePushUpCounter(RepListener listener) {
        this.listener = listener;
    }

    @Override public void reset() {
        resetTracking();
        awaitingTop = false;
        goodReps = badReps = formErrors = 0;
        displayedDepth = Float.NaN;
        phase = "等待开始";
        hint = "";
    }

    @Override public void process(PoseFrame frame) {
        long now = frame == null ? System.currentTimeMillis() : frame.getTimestampMs();
        Measurement m = measure(frame);
        if (m == null) {
            sample = null;
            samples = nextSample = topFrames = 0;
            topAngleSamples.clearWindow();
            topReachSamples.clearWindow();
            if (lastValidMs >= 0 && (now < lastValidMs || now - lastValidMs > DROPOUT_MS)) {
                resetTracking();
            }
            phase = "识别不完整";
            hint = "请让同一侧肩、肘、腕、髋、膝、踝完整入镜";
            return;
        }
        if (lastValidMs >= 0 && (now < lastValidMs || now - lastValidMs > DROPOUT_MS)) {
            resetTracking();
        }
        if (!m.inPike()) {
            resetTracking();
            phase = "未进入折刀姿态";
            hint = "侧对镜头，抬高髋部形成倒 V";
            return;
        }
        lastValidMs = now;
        sample = m;
        sampleElbows[nextSample] = m.elbow;
        sampleReaches[nextSample] = m.reach;
        sampleHips[nextSample] = m.hip;
        nextSample = (nextSample + 1) % SAMPLE_COUNT;
        samples = Math.min(SAMPLE_COUNT, samples + 1);
        observedTopAngle = topAngleSamples.observe(m.elbow);
        observedTopReach = topReachSamples.observe(m.reach);

        float depth = depthScore(m.elbow, angleTop());
        if (personal != null) depth /= personal.getTargetDepth();
        depth = clamp(depth, 0f, 1f);
        displayedDepth = Float.isNaN(displayedDepth) ? depth
                : displayedDepth + 0.5f * (depth - displayedDepth);
        boolean top = m.elbow >= topThreshold();
        if (awaitingTop) {
            if (top) {
                awaitingTop = false;
                armed = !Float.isNaN(observedTopReach);
                if (armed) {
                    baselineReach = observedTopReach;
                    armedMaxHip = m.hip;
                }
                phase = "撑起（可以开始下一次）";
                hint = "";
            } else {
                phase = "个人校准已计数";
                hint = "请先回到撑起位";
            }
            return;
        }

        if (downPhase) {
            repMinElbow = Math.min(repMinElbow, m.elbow);
            repMaxHip = Math.max(repMaxHip, m.hip);
            repMaxDrop = Math.max(repMaxDrop, baselineReach - m.reach);
            if (top) {
                finishRep(now - downStartMs);
                armed = true;
                armedMaxHip = m.hip;
                if (!Float.isNaN(observedTopReach)) baselineReach = observedTopReach;
            } else {
                phase = "下放中";
                hint = m.hip > hipLimit() ? "髋部保持抬高" : "";
            }
            return;
        }
        if (top) {
            if (!armed) topFrames++;
            if ((armed || topFrames >= SAMPLE_COUNT)
                    && !Float.isNaN(observedTopAngle) && !Float.isNaN(observedTopReach)) {
                if (!armed || Float.isNaN(armedMaxHip)) armedMaxHip = m.hip;
                else armedMaxHip = Math.max(armedMaxHip, m.hip);
                armed = true;
                baselineReach = observedTopReach;
            }
            phase = armed ? "撑起（准备下放）" : "撑起并保持片刻";
            hint = "";
            return;
        }
        if (!armed || Float.isNaN(baselineReach)) {
            topFrames = 0;
            phase = "请先回到撑起位";
            hint = "撑起并保持片刻，再开始下放";
            return;
        }
        armedMaxHip = Float.isNaN(armedMaxHip) ? m.hip : Math.max(armedMaxHip, m.hip);
        if (m.elbow < startThreshold()) {
            downPhase = true;
            downStartMs = now;
            repMinElbow = m.elbow;
            repMaxHip = Math.max(m.hip, armedMaxHip);
            repMaxDrop = Math.max(0f, baselineReach - m.reach);
            phase = "下放中";
            hint = "";
        }
    }

    private void finishRep(long duration) {
        float minElbow = repMinElbow, maxHip = repMaxHip, drop = repMaxDrop;
        downPhase = false;
        phase = "撑起";
        if (duration < MIN_REP_MS) {
            hint = "";
            return;
        }
        float requiredDrop = personal == null ? MIN_SHOULDER_DROP : personal.getMinMovement();
        if (drop < requiredDrop) {
            hint = "未观察到足够的上身下放";
            return;
        }
        float depth = depthScore(minElbow, angleTop());
        boolean clearlyMoved = minElbow <= PARTIAL_ANGLE
                || (personal != null && depth >= personal.getTargetDepth() * 0.65f);
        if (!clearlyMoved) {
            hint = "下放幅度较小，未计数";
            return;
        }
        boolean deepEnough = personal == null ? minElbow <= FULL_ANGLE
                : depth >= personal.getTargetDepth();
        boolean formOk = maxHip <= hipLimit();
        boolean good = deepEnough && formOk;
        String reason = !formOk ? "髋部抬高不足" : !deepEnough ? "下放幅度不足" : null;
        if (good) {
            goodReps++;
            if (personal != null && (personal.getStartingAngle() < TOP_ANGLE - 2f
                    || minElbow > FULL_ANGLE || maxHip > MAX_STANDARD_HIP
                    || drop < MIN_SHOULDER_DROP)) {
                formErrors++;
                hint = "按个人目标计数，仍需注意姿势";
            } else hint = "";
        } else {
            badReps++;
            formErrors++;
            hint = reason;
        }
        if (listener != null) listener.onRep(new RepEvent(good, reason, duration,
                goodReps, badReps, minElbow, maxHip));
    }

    private float angleTop() { return personal == null ? TOP_ANGLE : personal.getStartingAngle(); }
    private float topThreshold() { return angleTop() - 2f; }
    private float hipLimit() { return personal == null ? MAX_STANDARD_HIP : personal.getMaxForm(); }

    private float startThreshold() {
        if (personal == null) return 142f;
        float range = angleTop() - PersonalCalibration.referenceAngle(angleTop(), FULL_ANGLE);
        return angleTop() - Math.max(4f, Math.min(8f, range * personal.getTargetDepth() * 0.45f));
    }

    private static float depthScore(float elbow, float start) {
        float ref = PersonalCalibration.referenceAngle(start, FULL_ANGLE);
        return clamp((start - elbow) / (start - ref), 0f, PersonalCalibration.MAX_TARGET);
    }

    @Override public void setPersonalCalibration(PersonalCalibration calibration) {
        personal = calibration != null && calibration.isValid()
                && calibration.matches(ExerciseType.PIKE_PUSHUP, "") ? calibration : null;
    }

    @Override public PersonalCalibration capturePersonalCalibration(PoseFrame raw) {
        if (awaitingTop) {
            calibrationError = "本次已计数，请先回到撑起位";
            return null;
        }
        if (Float.isNaN(observedTopAngle) || Float.isNaN(observedTopReach)) {
            calibrationError = "请先撑起并保持片刻，再下放到当前最低位置";
            return null;
        }
        Measurement original = measure(raw);
        if (sample == null || original == null || !original.inPike() || samples < SAMPLE_COUNT
                || spread(sampleElbows) > 8f || spread(sampleReaches) > 0.08f
                || spread(sampleHips) > 10f
                || Math.abs(original.elbow - sample.elbow) > 12f
                || Math.abs(original.reach - sample.reach) > 0.12f
                || Math.abs(original.hip - sample.hip) > 15f) {
            calibrationError = "识别不完整或姿势仍在变化，请在最低位置保持片刻";
            return null;
        }
        float depth = depthScore(sample.elbow, observedTopAngle);
        float drop = observedTopReach - sample.reach;
        if (depth < 0.18f || drop < 0.025f) {
            calibrationError = "请先从撑起位屈臂并下放一些，再按个人校准";
            return null;
        }
        float target = clamp(depth * 0.95f, PersonalCalibration.MIN_TARGET, PersonalCalibration.MAX_TARGET);
        float minDrop = Math.min(MIN_SHOULDER_DROP, Math.max(0.02f, drop * 0.8f));
        float seenHip = sample.hip;
        if (downPhase) seenHip = Math.max(seenHip, repMaxHip);
        else if (!Float.isNaN(armedMaxHip)) seenHip = Math.max(seenHip, armedMaxHip);
        float maxHip = Math.min(MAX_TRACKED_HIP, Math.max(MAX_STANDARD_HIP, seenHip + 4f));
        boolean warning = observedTopAngle < TOP_ANGLE - 2f
                || sample.elbow > FULL_ANGLE || seenHip > MAX_STANDARD_HIP
                || drop < MIN_SHOULDER_DROP;
        return new PersonalCalibration(ExerciseType.PIKE_PUSHUP, "", target, sample.elbow,
                drop, maxHip, 0f, minDrop, false, warning, observedTopAngle);
    }

    @Override public String getCalibrationError() { return calibrationError; }

    @Override public void applyCalibrationEvent(PersonalCalibration calibration) {
        if (calibration == null || !calibration.isValid()
                || !calibration.matches(ExerciseType.PIKE_PUSHUP, "")) return;
        setPersonalCalibration(calibration);
        if (!Float.isNaN(observedTopReach)) baselineReach = observedTopReach;
        downPhase = false;
        armed = false;
        awaitingTop = true;
        goodReps++;
        if (calibration.hasFormWarning()) formErrors++;
        phase = "个人校准已计数";
        hint = "请先回到撑起位；个人目标不代表标准姿势";
        if (listener != null) listener.onRep(new RepEvent(true, null, 0, goodReps, badReps,
                calibration.getCapturedAngle(), calibration.getMaxForm()));
    }

    /** 不清除 awaitingTop，避免校准后丢帧造成同一次动作重复计数。 */
    private void resetTracking() {
        lastValidMs = -1;
        downPhase = false;
        armed = false;
        baselineReach = Float.NaN;
        armedMaxHip = Float.NaN;
        observedTopAngle = observedTopReach = Float.NaN;
        topAngleSamples.reset();
        topReachSamples.reset();
        sample = null;
        samples = nextSample = topFrames = 0;
    }

    private static float spread(float[] values) {
        float min = values[0], max = values[0];
        for (float v : values) { min = Math.min(min, v); max = Math.max(max, v); }
        return max - min;
    }

    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }

    private static final class Measurement {
        final float elbow, hip, reach, shoulderBelowHip, ankleBelowHip, confidence;
        Measurement(float elbow, float hip, float reach, float shoulderBelowHip,
                    float ankleBelowHip, float confidence) {
            this.elbow = elbow; this.hip = hip; this.reach = reach;
            this.shoulderBelowHip = shoulderBelowHip; this.ankleBelowHip = ankleBelowHip;
            this.confidence = confidence;
        }
        boolean inPike() {
            return shoulderBelowHip >= 0.08f && ankleBelowHip >= 0.20f
                    && reach > 0.02f && hip >= 40f && hip <= MAX_TRACKED_HIP;
        }
    }

    private static Measurement measure(PoseFrame frame) {
        if (frame == null || !frame.isVisible()) return null;
        Measurement left = measureSide(frame, LEFT), right = measureSide(frame, RIGHT);
        if (left == null) return right;
        if (right == null) return left;
        return left.confidence >= right.confidence ? left : right;
    }

    private static Measurement measureSide(PoseFrame frame, int[] p) {
        float confidence = 1f;
        for (int i : p) {
            float c = frame.getConfidence(i), x = frame.getX(i), y = frame.getY(i);
            if (!frame.isReliable(i, PoseMath.MIN_CONFIDENCE) || c > 1f
                    || Float.isNaN(x) || Float.isInfinite(x) || Float.isNaN(y) || Float.isInfinite(y)) return null;
            confidence = Math.min(confidence, c);
        }
        float torso = PoseMath.distance(frame.getX(p[0]), frame.getY(p[0]),
                frame.getX(p[3]), frame.getY(p[3]));
        float elbow = PoseMath.angle(frame, p[0], p[1], p[2]);
        float hip = PoseMath.angle(frame, p[0], p[3], p[5]);
        if (torso < 0.04f || Float.isNaN(torso) || Float.isInfinite(torso)
                || Float.isNaN(elbow) || Float.isInfinite(elbow)
                || Float.isNaN(hip) || Float.isInfinite(hip)) return null;
        return new Measurement(elbow, hip, (frame.getY(p[2]) - frame.getY(p[0])) / torso,
                (frame.getY(p[0]) - frame.getY(p[3])) / torso,
                (frame.getY(p[5]) - frame.getY(p[3])) / torso, confidence);
    }

    @Override public String getScoreText() { return goodReps + " 次"; }
    @Override public String getStatusText() { return hint.isEmpty() ? phase : phase + " · " + hint; }
    @Override public String getDetailText() {
        return String.format(Locale.US, "有效 %d · 待调整 %d · 姿势提醒 %d 次", goodReps, badReps, formErrors);
    }
    @Override public boolean hasResult() { return goodReps + badReps > 0; }
    @Override public int getGoodReps() { return goodReps; }
    @Override public int getBadReps() { return badReps; }
    @Override public int getFormErrors() { return formErrors; }
    @Override public float getDepthProgress() { return Float.isNaN(displayedDepth) ? 0f : displayedDepth; }
}
