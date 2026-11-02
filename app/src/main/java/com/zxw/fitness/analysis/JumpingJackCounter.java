package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.PoseMath;

import java.util.Locale;

/**
 * 正面或背面的开合跳：先建立合拢位，观察到双臂和双脚同时打开，回到合拢位后计数。
 * 这里只测量二维姿态，不能判断双脚是否离地、是否接触地面或动作是否安全。
 */
public final class JumpingJackCounter implements PersonalCalibrationAnalyzer {
    private static final float STANDARD_ARM = 135f;
    private static final float STANDARD_FOOT_DELTA = 0.35f; // 双踝间距增加 / 躯干长度
    private static final float CLOSED_ARM = 45f;
    private static final float CLOSED_FOOT_DELTA = 0.18f;
    private static final float MIN_FRONTAL_RATIO = 0.25f;
    private static final long MIN_CYCLE_MS = 250;
    private static final long DROPOUT_MS = 500;
    private static final long RESULT_HINT_MS = 1200;
    private static final int WINDOW = 3;
    private static final int[] REQUIRED = {PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER,
            PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP, PoseFrame.LEFT_WRIST, PoseFrame.RIGHT_WRIST,
            PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE};

    private final RepListener listener;
    private final float[] closedLeft = new float[WINDOW], closedRight = new float[WINDOW],
            closedLeftX = new float[WINDOW], closedRightX = new float[WINDOW];
    private final float[] sampleArm = new float[WINDOW], sampleArmMax = new float[WINDOW],
            sampleLeft = new float[WINDOW], sampleRight = new float[WINDOW],
            sampleLeftX = new float[WINDOW], sampleRightX = new float[WINDOW];
    private PersonalCalibration personal;
    private Measurement sample;
    private int closedSamples, samples, nextSample;
    private long lastValidMs = -1, openingStartedMs, resultHintUntilMs = -1;
    private float baseLeft = Float.NaN, baseRight = Float.NaN, calibrationClosedSpan = Float.NaN;
    private float baseLeftX = Float.NaN, baseRightX = Float.NaN;
    private float displayedProgress = Float.NaN, witnessArm, witnessFoot, witnessScore;
    private boolean opening, reachedTarget, reachedPartial, reachedStandard, awaitingClose;
    private int goodReps, badReps, formErrors;
    private String phase = "等待开始", hint = "", resultHint = "";
    private String calibrationError = "请先合拢，再在当前最佳张开位置保持片刻";

    public JumpingJackCounter(RepListener listener) { this.listener = listener; }

    @Override public void reset() {
        awaitingClose = false;
        calibrationClosedSpan = Float.NaN;
        resetTracking();
        goodReps = badReps = formErrors = 0;
        displayedProgress = Float.NaN;
        phase = "等待开始";
        hint = "";
    }

    @Override public void process(PoseFrame frame) {
        long now = frame == null ? System.currentTimeMillis() : frame.getTimestampMs();
        Measurement m = measure(frame);
        if (m == null) {
            sample = null;
            samples = nextSample = closedSamples = 0;
            if (lastValidMs >= 0 && (now < lastValidMs || now - lastValidMs > DROPOUT_MS)) {
                resetTracking();
            }
            phase = "识别不完整";
            hint = "请让双肩、双髋、双手腕和双脚踝完整入镜";
            return;
        }
        if (lastValidMs >= 0 && (now < lastValidMs || now - lastValidMs > DROPOUT_MS)) {
            resetTracking();
        }
        if (!m.uprightFrontal()) {
            resetTracking();
            phase = "请调整站姿";
            hint = "站直并尽量正对或背对镜头，保持全身入镜";
            return;
        }
        lastValidMs = now;
        sample = m;
        sampleArm[nextSample] = m.armMin;
        sampleArmMax[nextSample] = m.armMax;
        sampleLeft[nextSample] = m.left;
        sampleRight[nextSample] = m.right;
        sampleLeftX[nextSample] = m.leftX;
        sampleRightX[nextSample] = m.rightX;
        nextSample = (nextSample + 1) % WINDOW;
        samples = Math.min(WINDOW, samples + 1);

        if (awaitingClose) {
            if (absoluteClosed(m) && (Float.isNaN(calibrationClosedSpan)
                    || m.span() <= calibrationClosedSpan + closeFootDelta())) {
                awaitingClose = false;
                calibrationClosedSpan = Float.NaN;
                // 不用校准前的旧基准直接开始下一次；重新采样合拢位。
                baseLeft = baseRight = baseLeftX = baseRightX = Float.NaN;
                closedSamples = 0;
                collectClosed(m);
                phase = "已合拢，请保持片刻";
                hint = "";
            } else {
                phase = "个人校准已计数";
                hint = "请先放下双臂、合拢双脚";
            }
            displayedProgress = 0f;
            return;
        }

        if (Float.isNaN(baseLeft)) {
            if (absoluteClosed(m)) {
                collectClosed(m);
                phase = Float.isNaN(baseLeft) ? "合拢并保持片刻" : "合拢（准备开始）";
                hint = "";
            } else {
                closedSamples = 0;
                phase = "请先回到合拢位";
                hint = "放下双臂，双脚并拢并保持片刻";
            }
            displayedProgress = 0f;
            return;
        }

        float leftOut = baseLeft - m.left, rightOut = m.right - baseRight;
        float leftImageOut = (baseLeftX - m.leftX) / m.torso;
        float rightImageOut = (m.rightX - baseRightX) / m.torso;
        float footDelta = leftOut + rightOut;
        float armTarget = armTarget(), footTarget = footTarget();
        float sideTarget = perSideTarget(footTarget);
        float weakestSide = Math.min(Math.min(leftOut, rightOut), Math.min(leftImageOut, rightImageOut));
        float footProgress = Math.min(footDelta / footTarget, weakestSide / sideTarget);
        if (personal == null) footProgress = Math.min(footProgress, m.span() / (m.shoulderWidth * 1.15f));
        float progress = clamp(Math.min(m.armMin / armTarget, footProgress), 0f, 1f);
        if (personal == null && !m.wristsAboveShoulders) progress = Math.min(progress, 0.99f);
        displayedProgress = Float.isNaN(displayedProgress) ? progress
                : displayedProgress + 0.5f * (progress - displayedProgress);
        boolean closed = absoluteClosed(m) && footDelta <= closeFootDelta();
        if (!opening) {
            if (closed) {
                // 只在合拢位缓慢更新个人起点，不能将张开位学成起点。
                updateClosedBaseline(m);
                phase = "合拢（准备开始）";
                hint = "";
                return;
            }
            if (m.armMax > Math.min(55f, armTarget * 0.8f)
                    || leftOut > Math.min(0.08f, footTarget * 0.25f)
                    || rightOut > Math.min(0.08f, footTarget * 0.25f)) {
                opening = true;
                openingStartedMs = now;
                reachedTarget = reachedPartial = reachedStandard = false;
                witnessArm = witnessFoot = witnessScore = 0f;
            } else {
                phase = "准备打开";
                hint = "";
                return;
            }
        }

        // 所有证据必须来自同一帧，不把先挥臂、再单独迈腿的两个峰值拼成一次。
        boolean standard = standardOpen(m, footDelta, leftOut, rightOut, leftImageOut, rightImageOut);
        boolean target = personal == null ? standard : m.armMin >= armTarget
                && feetOpen(footDelta, leftOut, rightOut, leftImageOut, rightImageOut, footTarget);
        boolean partial = m.armMin >= Math.min(70f, armTarget * 0.75f)
                && feetOpen(footDelta, leftOut, rightOut, leftImageOut, rightImageOut,
                Math.min(0.15f, footTarget * 0.75f));
        reachedTarget |= target;
        reachedPartial |= partial || target;
        reachedStandard |= standard;
        if (partial || target) {
            float score = Math.min(m.armMin / armTarget, Math.max(0f, footDelta) / footTarget);
            if (score > witnessScore) {
                witnessScore = score;
                witnessArm = m.armMin;
                witnessFoot = footDelta;
            }
        }
        if (closed) {
            finishCycle(now - openingStartedMs);
            updateClosedBaseline(m);
        } else {
            phase = reachedTarget ? "已打开，请合拢" : "打开中";
            hint = "双臂和双脚同时打开，再合拢";
        }
    }

    private void collectClosed(Measurement m) {
        closedLeft[closedSamples] = m.left;
        closedRight[closedSamples] = m.right;
        closedLeftX[closedSamples] = m.leftX;
        closedRightX[closedSamples] = m.rightX;
        closedSamples++;
        if (closedSamples < WINDOW) return;
        if (spread(closedLeft) <= 0.12f && spread(closedRight) <= 0.12f) {
            // 取窗口里更宽的一对，避免偶发脚踝抖动放大张开幅度。
            baseLeft = min(closedLeft);
            baseRight = max(closedRight);
            baseLeftX = min(closedLeftX);
            baseRightX = max(closedRightX);
        } else {
            closedSamples = 0;
        }
    }

    private void updateClosedBaseline(Measurement m) {
        baseLeft = 0.8f * baseLeft + 0.2f * m.left;
        baseRight = 0.8f * baseRight + 0.2f * m.right;
        baseLeftX = 0.8f * baseLeftX + 0.2f * m.leftX;
        baseRightX = 0.8f * baseRightX + 0.2f * m.rightX;
    }

    private void finishCycle(long duration) {
        opening = false;
        phase = "合拢";
        if (duration < MIN_CYCLE_MS || !reachedPartial) {
            hint = "双臂和双脚需同时打开，未计数";
            rememberResultHint();
            return;
        }
        String reason = reachedTarget ? null : "双臂或双脚张开幅度不足";
        if (reachedTarget) {
            goodReps++;
            if (personal != null && !reachedStandard) {
                formErrors++;
                hint = "按个人目标计数，仍需注意姿势";
            } else hint = "";
        } else {
            badReps++;
            formErrors++;
            hint = reason;
        }
        rememberResultHint();
        if (listener != null) listener.onRep(new RepEvent(reachedTarget, reason, duration,
                goodReps, badReps, witnessArm, witnessFoot));
    }

    private void rememberResultHint() {
        resultHint = hint;
        resultHintUntilMs = lastValidMs < 0 ? -1 : lastValidMs + RESULT_HINT_MS;
    }

    private float armTarget() { return personal == null ? STANDARD_ARM : STANDARD_ARM * personal.getTargetDepth(); }
    private float footTarget() { return personal == null ? STANDARD_FOOT_DELTA : personal.getMinMovement(); }
    private float closeFootDelta() { return Math.min(CLOSED_FOOT_DELTA, footTarget() * 0.5f); }

    /** 两脚相对髋部和固定画面都须各自向外，减少髋偏移造成单脚迈步误计。 */
    private static boolean feetOpen(float delta, float left, float right,
                                    float imageLeft, float imageRight, float target) {
        float perSide = perSideTarget(target);
        return delta >= target && left >= perSide && right >= perSide
                && imageLeft >= perSide && imageRight >= perSide;
    }

    private static float perSideTarget(float target) {
        return Math.max(0.04f, Math.min(0.10f, target * 0.28f));
    }

    private static boolean standardOpen(Measurement m, float delta, float left, float right,
                                        float imageLeft, float imageRight) {
        return m.armMin >= STANDARD_ARM && m.wristsAboveShoulders
                && feetOpen(delta, left, right, imageLeft, imageRight, STANDARD_FOOT_DELTA)
                && m.span() >= m.shoulderWidth * 1.15f;
    }

    private static boolean absoluteClosed(Measurement m) {
        float maxSpan = Math.min(1.6f, Math.max(1.05f, m.shoulderWidth * 1.4f));
        return m.armMax <= CLOSED_ARM && m.span() <= maxSpan;
    }

    @Override public void setPersonalCalibration(PersonalCalibration calibration) {
        personal = calibration != null && calibration.isValid()
                && calibration.matches(ExerciseType.JUMPING_JACK, "") ? calibration : null;
    }

    @Override public PersonalCalibration capturePersonalCalibration(PoseFrame raw) {
        if (awaitingClose) {
            calibrationError = "本次已计数，请先放下双臂并合拢双脚";
            return null;
        }
        if (Float.isNaN(baseLeft) || !opening) {
            calibrationError = "请先合拢并保持片刻，再同时打开双臂和双脚";
            return null;
        }
        Measurement original = measure(raw);
        if (sample == null || original == null || !original.uprightFrontal() || samples < WINDOW
                || spread(sampleArm) > 12f || spread(sampleArmMax) > 12f
                || spread(sampleLeft) > 0.12f || spread(sampleRight) > 0.12f
                || spread(sampleLeftX) / sample.torso > 0.12f || spread(sampleRightX) / sample.torso > 0.12f
                || Math.abs(original.leftX - sample.leftX) / sample.torso > 0.15f
                || Math.abs(original.rightX - sample.rightX) / sample.torso > 0.15f
                || Math.abs(original.armMin - sample.armMin) > 15f
                || Math.abs(original.armMax - sample.armMax) > 15f
                || Math.abs(original.left - sample.left) > 0.15f
                || Math.abs(original.right - sample.right) > 0.15f) {
            calibrationError = "识别不完整或姿势仍在变化，请在张开位置保持片刻";
            return null;
        }
        float left = baseLeft - sample.left, right = sample.right - baseRight, delta = left + right;
        float imageLeft = (baseLeftX - sample.leftX) / sample.torso;
        float imageRight = (sample.rightX - baseRightX) / sample.torso;
        float rawLeft = baseLeft - original.left, rawRight = original.right - baseRight;
        float rawImageLeft = (baseLeftX - original.leftX) / original.torso;
        float rawImageRight = (original.rightX - baseRightX) / original.torso;
        if (sample.armMin < 55f || delta < 0.12f || left < 0.04f || right < 0.04f
                || imageLeft < 0.04f || imageRight < 0.04f
                || original.armMin < 55f || rawLeft < 0.04f || rawRight < 0.04f
                || rawImageLeft < 0.04f || rawImageRight < 0.04f || rawLeft + rawRight < 0.12f) {
            calibrationError = "请从合拢位同时抬起双臂、向外打开双脚后再校准";
            return null;
        }
        float targetArm = Math.min(STANDARD_ARM, sample.armMin * 0.95f);
        float smallerSide = Math.min(Math.min(left, right), Math.min(imageLeft, imageRight));
        float targetFeet = Math.max(0.10f, Math.min(STANDARD_FOOT_DELTA,
                Math.min(delta * 0.8f, smallerSide * 0.95f / 0.28f)));
        boolean warning = !standardOpen(sample, delta, left, right, imageLeft, imageRight);
        return new PersonalCalibration(ExerciseType.JUMPING_JACK, "", targetArm / STANDARD_ARM,
                sample.armMin, delta, 0f, 0f, targetFeet, false, warning);
    }

    @Override public String getCalibrationError() { return calibrationError; }

    @Override public void applyCalibrationEvent(PersonalCalibration calibration) {
        if (awaitingClose || calibration == null || !calibration.isValid()
                || !calibration.matches(ExerciseType.JUMPING_JACK, "")) return;
        setPersonalCalibration(calibration);
        calibrationClosedSpan = Float.isNaN(baseLeft) ? Float.NaN : baseRight - baseLeft;
        opening = false;
        awaitingClose = true;
        resultHint = "";
        resultHintUntilMs = -1;
        goodReps++;
        if (calibration.hasFormWarning()) formErrors++;
        phase = "个人校准已计数";
        hint = "请先合拢；个人目标不代表标准姿势";
        if (listener != null) listener.onRep(new RepEvent(true, null, 0, goodReps, badReps,
                calibration.getCapturedAngle(), calibration.getCapturedMovement()));
    }

    /** 长时间缺帧或站姿失效时丢弃周期；校准后的等待合拢条件必须保留。 */
    private void resetTracking() {
        lastValidMs = -1;
        opening = reachedTarget = reachedPartial = reachedStandard = false;
        baseLeft = baseRight = baseLeftX = baseRightX = Float.NaN;
        closedSamples = samples = nextSample = 0;
        sample = null;
        displayedProgress = Float.NaN;
        resultHint = "";
        resultHintUntilMs = -1;
    }

    private static Measurement measure(PoseFrame frame) {
        if (frame == null || !frame.isVisible()) return null;
        for (int i : REQUIRED) {
            float c = frame.getConfidence(i), x = frame.getX(i), y = frame.getY(i);
            if (!frame.isReliable(i, PoseMath.MIN_CONFIDENCE) || c > 1f
                    || !finite(x) || !finite(y)) return null;
        }
        float sx = (frame.getX(PoseFrame.LEFT_SHOULDER) + frame.getX(PoseFrame.RIGHT_SHOULDER)) / 2f;
        float sy = (frame.getY(PoseFrame.LEFT_SHOULDER) + frame.getY(PoseFrame.RIGHT_SHOULDER)) / 2f;
        float hx = (frame.getX(PoseFrame.LEFT_HIP) + frame.getX(PoseFrame.RIGHT_HIP)) / 2f;
        float hy = (frame.getY(PoseFrame.LEFT_HIP) + frame.getY(PoseFrame.RIGHT_HIP)) / 2f;
        float torso = PoseMath.distance(sx, sy, hx, hy);
        if (!finite(torso) || torso < 0.04f) return null;
        float lArm = PoseMath.angle(frame, PoseFrame.LEFT_HIP, PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_WRIST);
        float rArm = PoseMath.angle(frame, PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_WRIST);
        if (!finite(lArm) || !finite(rArm)) return null;
        float ankleX1 = frame.getX(PoseFrame.LEFT_ANKLE), ankleX2 = frame.getX(PoseFrame.RIGHT_ANKLE);
        float leftAnkle = (ankleX1 - hx) / torso;
        float rightAnkle = (ankleX2 - hx) / torso;
        float ankleY = (frame.getY(PoseFrame.LEFT_ANKLE) + frame.getY(PoseFrame.RIGHT_ANKLE)) / 2f;
        float shoulderWidth = Math.abs(frame.getX(PoseFrame.LEFT_SHOULDER)
                - frame.getX(PoseFrame.RIGHT_SHOULDER)) / torso;
        return new Measurement(Math.min(lArm, rArm), Math.max(lArm, rArm),
                Math.min(leftAnkle, rightAnkle), Math.max(leftAnkle, rightAnkle), shoulderWidth,
                (hy - sy) / torso, (ankleY - hy) / torso,
                Math.min(ankleX1, ankleX2), Math.max(ankleX1, ankleX2), torso,
                frame.getY(PoseFrame.LEFT_WRIST) < frame.getY(PoseFrame.LEFT_SHOULDER)
                        && frame.getY(PoseFrame.RIGHT_WRIST) < frame.getY(PoseFrame.RIGHT_SHOULDER));
    }

    private static final class Measurement {
        final float armMin, armMax, left, right, shoulderWidth, hipBelowShoulder, ankleBelowHip;
        final float leftX, rightX, torso;
        final boolean wristsAboveShoulders;
        Measurement(float armMin, float armMax, float left, float right, float shoulderWidth,
                    float hipBelowShoulder, float ankleBelowHip, float leftX, float rightX,
                    float torso, boolean wristsAboveShoulders) {
            this.armMin = armMin; this.armMax = armMax; this.left = left; this.right = right;
            this.shoulderWidth = shoulderWidth; this.hipBelowShoulder = hipBelowShoulder;
            this.ankleBelowHip = ankleBelowHip; this.leftX = leftX; this.rightX = rightX;
            this.torso = torso; this.wristsAboveShoulders = wristsAboveShoulders;
        }
        float span() { return right - left; }
        boolean uprightFrontal() {
            return shoulderWidth >= MIN_FRONTAL_RATIO && hipBelowShoulder >= 0.5f && ankleBelowHip >= 0.5f;
        }
    }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
    private static float min(float[] values) { float v = values[0]; for (float x : values) v = Math.min(v, x); return v; }
    private static float max(float[] values) { float v = values[0]; for (float x : values) v = Math.max(v, x); return v; }
    private static float spread(float[] values) { return max(values) - min(values); }

    @Override public String getScoreText() { return goodReps + " 次"; }
    @Override public String getStatusText() {
        String text = hint;
        if (text.isEmpty() && lastValidMs >= 0 && lastValidMs <= resultHintUntilMs) text = resultHint;
        return text.isEmpty() ? phase : phase + " · " + text;
    }
    @Override public String getDetailText() {
        return String.format(Locale.US, "有效 %d · 待调整 %d · 姿势提醒 %d 次", goodReps, badReps, formErrors);
    }
    @Override public boolean hasResult() { return goodReps + badReps > 0; }
    @Override public int getGoodReps() { return goodReps; }
    @Override public int getBadReps() { return badReps; }
    @Override public int getFormErrors() { return formErrors; }
    @Override public float getDepthProgress() { return Float.isNaN(displayedProgress) ? 0f : displayedProgress; }
}
