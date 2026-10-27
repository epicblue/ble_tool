package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.PoseMath;

import java.util.Locale;

/**
 * 俯卧撑计数器（俯卧撑 / 折凳俯卧撑 / 不标准俯卧撑共用）。
 *
 * <p>判定思路：</p>
 * <ul>
 *     <li>用肩-肘-腕夹角判断"撑起/下放"两个阶段，肘角大于 {@link #TOP_ANGLE} 视为撑起，
 *     下降到 {@link #DEPTH_ANGLE} 以下视为到位。</li>
 *     <li>一次完整的往返计一次：到位且身体保持一条直线 → 有效；幅度不足或塌腰/撅臀、
 *     跪姿 → 记为"不标准"，同时累加姿势问题次数。</li>
 *     <li>只在躯干接近水平、双手低于肩部时计数，避免站着挥动手臂被误识别。</li>
 * </ul>
 */
public class PushUpCounter implements ExerciseAnalyzer {

    public interface Listener {
        /** 完成一次动作（无论是否标准）。 */
        void onRep(RepEvent event);
    }

    /** 一次动作的结果。 */
    public static class RepEvent {
        public final boolean valid;
        public final float minElbowAngle;
        public final float minHipAngle;
        public final float minKneeAngle;
        public final long durationMs;
        public final String reason;

        RepEvent(boolean valid, float minElbowAngle, float minHipAngle, float minKneeAngle,
                 long durationMs, String reason) {
            this.valid = valid;
            this.minElbowAngle = minElbowAngle;
            this.minHipAngle = minHipAngle;
            this.minKneeAngle = minKneeAngle;
            this.durationMs = durationMs;
            this.reason = reason;
        }
    }

    // ---- 阈值 ----
    /** 手臂接近伸直的角度。 */
    private static final float TOP_ANGLE = 152f;
    /** 看到这个角度就认为开始下放。 */
    private static final float START_DOWN_ANGLE = 132f;
    /** 到位（有效深度）的角度。 */
    private static final float DEPTH_ANGLE = 100f;
    /** 低于这个角度才算"明显做了但不够深"，否则忽略（避免抖动误计）。 */
    private static final float PARTIAL_ANGLE = 122f;
    /** 髋部直线度阈值：肩-髋-踝夹角小于它说明塌腰或撅臀。 */
    private static final float HIP_STRAIGHT_ANGLE = 150f;
    /** 膝盖弯曲到该角度以下视为跪姿。 */
    private static final float KNEEL_ANGLE = 120f;
    /** 一次动作的最短时长，过滤抖动。 */
    private static final long MIN_REP_MS = 350;
    /** 肘角平滑系数（仅用于阶段切换，深度判定用原始角度以免滞后）。 */
    private static final float SMOOTHING = 0.5f;
    /** 躯干与水平方向的最大夹角（度）。 */
    private static final float MAX_TORSO_HORIZONTAL = 55f;

    private final ExerciseType type;
    private final String variant;
    private final Listener listener;

    private float smoothedElbow = Float.NaN;
    private float lastHipAngle = Float.NaN;
    private float lastKneeAngle = Float.NaN;
    private float lastElbow = Float.NaN;

    private boolean downPhase;
    private long downStartMs;
    private float repMinElbow = Float.NaN;
    private float repMinHip = Float.NaN;
    private float repMinKnee = Float.NaN;
    private boolean sawValidPosture;

    private int goodReps;
    private int badReps;
    private int formErrors;
    private String hint = "";
    private String phaseText = "等待开始";

    public PushUpCounter(ExerciseType type, String variant, Listener listener) {
        this.type = type == null ? ExerciseType.PUSHUP : type;
        this.variant = variant == null ? "" : variant;
        this.listener = listener;
    }

    /** 折凳"手撑凳"变体：底部角度略放宽（斜面上身体行程更小）。 */
    private float depthAngle() {
        if (type == ExerciseType.CHAIR_PUSHUP && isHandsOnChair()) {
            return DEPTH_ANGLE + 6f;
        }
        return DEPTH_ANGLE;
    }

    private float partialAngle() {
        return Math.max(depthAngle() + 8f, PARTIAL_ANGLE);
    }

    private boolean isHandsOnChair() {
        return "hands".equals(variant);
    }

    @Override
    public void reset() {
        smoothedElbow = Float.NaN;
        lastHipAngle = Float.NaN;
        lastKneeAngle = Float.NaN;
        lastElbow = Float.NaN;
        downPhase = false;
        downStartMs = 0;
        repMinElbow = Float.NaN;
        repMinHip = Float.NaN;
        repMinKnee = Float.NaN;
        sawValidPosture = false;
        goodReps = 0;
        badReps = 0;
        formErrors = 0;
        hint = "";
        phaseText = "等待开始";
    }

    @Override
    public void process(PoseFrame frame) {
        if (frame == null || !frame.isVisible()) {
            if (downPhase) {
                downPhase = false;
            }
            phaseText = "未检测到人体";
            hint = "请让全身进入画面（建议侧面对着手机）";
            return;
        }

        float elbow = PoseMath.averageAngle(frame,
                PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST);
        if (Float.isNaN(elbow)) {
            phaseText = "请让手臂完整入镜";
            hint = "手臂被遮挡或超出画面";
            return;
        }
        smoothedElbow = Float.isNaN(smoothedElbow)
                ? elbow
                : smoothedElbow + SMOOTHING * (elbow - smoothedElbow);
        lastElbow = smoothedElbow;

        float hip = PoseMath.averageAngle(frame,
                PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_HIP, PoseFrame.LEFT_ANKLE,
                PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_ANKLE);
        float knee = PoseMath.averageAngle(frame,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE);
        lastHipAngle = hip;
        lastKneeAngle = knee;

        // 双手应低于肩（撑在地面或凳面上），躯干接近水平：否则视为没有进入俯卧撑姿态
        boolean handsBelowShoulders = handsBelowShoulders(frame);
        float torsoHorizontal = PoseMath.torsoAngleFromHorizontal(frame);
        boolean postureOk = handsBelowShoulders
                && (Float.isNaN(torsoHorizontal) || torsoHorizontal < MAX_TORSO_HORIZONTAL);

        if (!postureOk) {
            if (downPhase) {
                downPhase = false;
            }
            phaseText = "未进入俯卧撑姿态";
            hint = "侧对镜头，双手撑地/撑凳，身体放平";
            return;
        }

        // ---- 阶段状态机 ----
        if (!downPhase) {
            if (smoothedElbow < START_DOWN_ANGLE) {
                downPhase = true;
                downStartMs = frame.getTimestampMs();
                repMinElbow = elbow;
                repMinHip = hip;
                repMinKnee = knee;
                sawValidPosture = true;
            } else {
                phaseText = smoothedElbow >= TOP_ANGLE ? "撑起（准备下放）" : "下放中";
                hint = "";
            }
        } else {
            repMinElbow = min(repMinElbow, elbow);
            repMinHip = min(repMinHip, hip);
            repMinKnee = min(repMinKnee, knee);
            if (!Float.isNaN(hip) && hip < HIP_STRAIGHT_ANGLE) {
                sawValidPosture = false;
            }
            if (!Float.isNaN(knee) && knee < KNEEL_ANGLE) {
                sawValidPosture = false;
            }

            if (smoothedElbow >= TOP_ANGLE) {
                long duration = frame.getTimestampMs() - downStartMs;
                finishRep(duration);
            } else {
                phaseText = "下放中";
                hint = formHint(hip, knee);
            }
        }
    }

    private void finishRep(long durationMs) {
        float minElbow = Float.isNaN(repMinElbow) ? 180f : repMinElbow;
        float minHip = Float.isNaN(repMinHip) ? 180f : repMinHip;
        float minKnee = Float.isNaN(repMinKnee) ? 180f : repMinKnee;
        // 先保存本次动作的姿势标记，再清空状态
        boolean postureOk = sawValidPosture;

        downPhase = false;
        repMinElbow = Float.NaN;
        repMinHip = Float.NaN;
        repMinKnee = Float.NaN;
        sawValidPosture = true;

        if (durationMs < MIN_REP_MS) {
            phaseText = "撑起";
            return; // 抖动，忽略
        }

        boolean deepEnough = minElbow <= depthAngle();
        boolean clearlyMoved = minElbow <= partialAngle();
        boolean bodyOk = minHip >= HIP_STRAIGHT_ANGLE && minKnee >= KNEEL_ANGLE && postureOk;

        String reason = null;
        if (!bodyOk) {
            reason = minKnee < KNEEL_ANGLE ? "膝盖弯曲（跪姿）" : "塌腰/撅臀，身体未成直线";
        } else if (!deepEnough) {
            reason = "下放幅度不足";
        }

        if (!bodyOk) {
            badReps++;
            formErrors++;
        } else if (deepEnough) {
            goodReps++;
        } else if (clearlyMoved) {
            badReps++;
            formErrors++;
        } else {
            // 幅度太小，不计数
            phaseText = "幅度不足，未计数";
            hint = "胸口再往下放一些";
            return;
        }

        phaseText = "撑起";
        hint = reason == null ? "" : reason;
        if (listener != null) {
            listener.onRep(new RepEvent(reason == null, minElbow, minHip, minKnee,
                    durationMs, reason));
        }
    }

    private String formHint(float hip, float knee) {
        if (!Float.isNaN(knee) && knee < KNEEL_ANGLE) {
            return "膝盖弯曲了，伸直双腿";
        }
        if (!Float.isNaN(hip) && hip < HIP_STRAIGHT_ANGLE) {
            return "塌腰/撅臀，请收紧核心";
        }
        return "";
    }

    private boolean handsBelowShoulders(PoseFrame f) {
        boolean leftOk = PoseMath.isReliable(f, PoseFrame.LEFT_WRIST)
                && PoseMath.isReliable(f, PoseFrame.LEFT_SHOULDER);
        boolean rightOk = PoseMath.isReliable(f, PoseFrame.RIGHT_WRIST)
                && PoseMath.isReliable(f, PoseFrame.RIGHT_SHOULDER);
        int count = 0;
        float diff = 0;
        if (leftOk) {
            diff += f.getY(PoseFrame.LEFT_WRIST) - f.getY(PoseFrame.LEFT_SHOULDER);
            count++;
        }
        if (rightOk) {
            diff += f.getY(PoseFrame.RIGHT_WRIST) - f.getY(PoseFrame.RIGHT_SHOULDER);
            count++;
        }
        if (count == 0) {
            return true; // 手腕不可见时不阻塞判定
        }
        return diff / count > 0.01f;
    }

    private static float min(float current, float candidate) {
        if (Float.isNaN(candidate)) {
            return current;
        }
        if (Float.isNaN(current)) {
            return candidate;
        }
        return Math.min(current, candidate);
    }

    @Override
    public String getScoreText() {
        if (type == ExerciseType.BAD_PUSHUP) {
            return badReps + " 次";
        }
        return goodReps + " 次";
    }

    public int getGoodReps() {
        return goodReps;
    }

    public int getBadReps() {
        return badReps;
    }

    public int getFormErrors() {
        return formErrors;
    }

    public float getCurrentElbowAngle() {
        return lastElbow;
    }

    public float getCurrentHipAngle() {
        return lastHipAngle;
    }

    /** 当前下放深度进度 0~1，用于界面上的进度条。 */
    public float getDepthProgress() {
        if (Float.isNaN(smoothedElbow)) {
            return 0f;
        }
        float progress = (TOP_ANGLE - smoothedElbow) / (TOP_ANGLE - depthAngle());
        return Math.max(0f, Math.min(1f, progress));
    }

    @Override
    public String getStatusText() {
        if (hint != null && hint.length() > 0) {
            return phaseText + " · " + hint;
        }
        return phaseText;
    }

    @Override
    public String getDetailText() {
        return String.format(Locale.US, "有效 %d · 不标准 %d · 姿势提醒 %d 次",
                goodReps, badReps, formErrors);
    }

    @Override
    public boolean hasResult() {
        return goodReps + badReps > 0;
    }

    /** 当前动作类型。 */
    public ExerciseType getType() {
        return type;
    }
}
