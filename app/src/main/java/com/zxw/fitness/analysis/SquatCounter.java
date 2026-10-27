package com.zxw.fitness.analysis;

import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.BodyMetrics;
import com.zxw.fitness.pose.PoseMath;

import java.util.Locale;

/**
 * 深蹲计数器。
 *
 * <p>判定：站立（膝角接近伸直）→ 下蹲（膝角变小、髋部下降）→ 起立 计一次。</p>
 * <ul>
 *     <li>深度取两路信号里更深的那个：膝角（髋-膝-踝）≤ {@link #DEPTH_ANGLE}（约大腿平行地面），
 *     <b>或者</b>髋部相对站立位的下降量 ≥ {@link #HIP_TRAVEL_DEEP} × 躯干长度。
 *     正对/斜对镜头时膝角在图像上被透视压扁（实测 41° 斜对时 92° 的到位角被读成 125°），
 *     只靠膝角会整组漏计，所以两路互相兜底；</li>
 *     <li>至少要求髋部真的动了：下降量 ≥ {@link #MIN_HIP_TRAVEL} × 躯干长度，
 *     避免只弯膝不下蹲被误计；</li>
 *     <li>身体过度前倾（躯干与竖直夹角 &gt; {@link #MAX_TORSO_LEAN}）记为不标准；</li>
 *     <li>只适用于站立姿态（髋在肩下方、脚在髋下方），俯卧/倒立时不计。</li>
 * </ul>
 */
public class SquatCounter implements RepAnalyzer {

    /** 到位深度（膝角，度）。 */
    private static final float DEPTH_ANGLE = 100f;
    /** 明显下蹲但不到位（膝角，度）。 */
    private static final float PARTIAL_ANGLE = 125f;
    /** 站立判定（膝角，度）。 */
    private static final float STAND_ANGLE = 155f;
    /** 开始下蹲的膝角阈值。 */
    private static final float START_DOWN_ANGLE = 140f;
    /** 髋部最小下降量（×躯干长度），防止"抖膝"被计数。 */
    private static final float MIN_HIP_TRAVEL = 0.12f;
    /** 髋部下移 ≥ 该比例（×躯干长度）视为蹲到位：正对/斜对镜头时膝角被透视压扁，主要靠它。 */
    private static final float HIP_TRAVEL_DEEP = 0.35f;
    /** 髋部下移 ≥ 该比例视为"明显下蹲但不到位"。 */
    private static final float HIP_TRAVEL_PARTIAL = 0.18f;
    /** 躯干前倾上限（度）。 */
    private static final float MAX_TORSO_LEAN = 55f;
    /** 一次动作最短时长。 */
    private static final long MIN_REP_MS = 320;
    /** 允许的人体丢失时长。 */
    private static final long DROPOUT_MS = 500;
    /** 平滑系数。 */
    private static final float SMOOTHING = 0.5f;

    private final RepListener listener;

    private float smoothedKnee = Float.NaN;
    private float smoothedHipY = Float.NaN;
    /** 站立时的髋部高度基准（图像 y，越小越高）。 */
    private float standHipY = Float.NaN;
    private float lastHipTravel;

    private boolean downPhase;
    private long downStartMs;
    private float repMinKnee;
    private float repMaxLean;
    private float repMaxTravel;

    private int goodReps;
    private int badReps;
    private int formErrors;
    private String hint = "";
    private String phaseText = "等待开始";
    private long lastValidMs = -1;

    public SquatCounter(RepListener listener) {
        this.listener = listener;
    }

    @Override
    public void reset() {
        smoothedKnee = Float.NaN;
        smoothedHipY = Float.NaN;
        standHipY = Float.NaN;
        lastHipTravel = 0f;
        downPhase = false;
        downStartMs = 0;
        repMinKnee = Float.NaN;
        repMaxLean = 0f;
        repMaxTravel = 0f;
        goodReps = 0;
        badReps = 0;
        formErrors = 0;
        hint = "";
        phaseText = "等待开始";
        lastValidMs = -1;
    }

    @Override
    public void process(PoseFrame frame) {
        long now = frame == null ? System.currentTimeMillis() : frame.getTimestampMs();
        if (frame == null || !frame.isVisible()) {
            handleDropout(now);
            return;
        }
        BodyMetrics metrics = BodyMetrics.of(frame);
        if (!metrics.valid) {
            phaseText = "未完整识别到身体";
            hint = "请让肩、髋、膝盖都进入画面";
            handleDropout(now);
            return;
        }
        lastValidMs = now;

        // 站姿门槛：髋在肩下方，脚踝在髋下方
        boolean hipBelowShoulder = metrics.hipY > metrics.shoulderY + 0.02f;
        boolean ankleBelowHip = !metrics.hasAnkle || metrics.ankleY > metrics.hipY - 0.02f;
        if (!hipBelowShoulder || !ankleBelowHip || metrics.isInverted()) {
            dropPhase();
            phaseText = "未进入深蹲站姿";
            hint = "请正对或侧对镜头站直，全身入镜";
            return;
        }

        float knee = PoseMath.averageAngle(frame,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE);
        if (Float.isNaN(knee)) {
            phaseText = "请让腿部完整入镜";
            hint = "膝盖或脚踝被遮挡";
            hidePartial();
            return;
        }
        smoothedKnee = Float.isNaN(smoothedKnee)
                ? knee
                : smoothedKnee + SMOOTHING * (knee - smoothedKnee);
        smoothedHipY = Float.isNaN(smoothedHipY)
                ? metrics.hipY
                : smoothedHipY + SMOOTHING * (metrics.hipY - smoothedHipY);

        // 站立基准：膝盖伸直时记录髋高（取"最高"的那次，抵消镜头轻微移动）
        if (knee >= STAND_ANGLE) {
            standHipY = Float.isNaN(standHipY)
                    ? smoothedHipY
                    : Math.min(standHipY, smoothedHipY);
        }
        float travel = Float.isNaN(standHipY)
                ? 0f
                : (smoothedHipY - standHipY) / Math.max(0.04f, metrics.bodyScale);
        lastHipTravel = travel;

        float lean = metrics.torsoAngleFromVertical();
        if (Float.isNaN(lean)) {
            lean = 0f;
        }

        if (!downPhase) {
            if (smoothedKnee < START_DOWN_ANGLE) {
                downPhase = true;
                downStartMs = now;
                repMinKnee = knee;
                repMaxLean = lean;
                repMaxTravel = travel;
            } else {
                phaseText = knee >= STAND_ANGLE ? "站立（可以开始下蹲）" : "下蹲中";
                hint = "";
                return;
            }
        } else {
            repMinKnee = Math.min(repMinKnee, knee);
            repMaxLean = Math.max(repMaxLean, lean);
            repMaxTravel = Math.max(repMaxTravel, travel);
        }

        if (smoothedKnee >= STAND_ANGLE) {
            if (downPhase) {
                finishRep(now - downStartMs);
            }
            phaseText = "站直";
        } else {
            phaseText = "下蹲中";
            hint = depthHint(repMaxLean, repMaxTravel, repMinKnee);
        }
    }

    private void finishRep(long durationMs) {
        float minKnee = repMinKnee;
        float maxLean = repMaxLean;
        float travel = repMaxTravel;

        downPhase = false;
        repMinKnee = Float.NaN;
        repMaxLean = 0f;
        repMaxTravel = 0f;

        if (durationMs < MIN_REP_MS) {
            phaseText = "站直";
            return;
        }

        // 膝角在侧对镜头时最准；髋部下移沿重力方向量，正对/斜对镜头都不受影响，
        // 两者都是"深度的下限估计"，取较大者可以覆盖不同机位。
        boolean deepEnough = minKnee <= DEPTH_ANGLE || travel >= HIP_TRAVEL_DEEP;
        boolean clearlyMoved = minKnee <= PARTIAL_ANGLE || travel >= HIP_TRAVEL_PARTIAL;
        boolean hipMoved = travel >= MIN_HIP_TRAVEL;
        boolean leanOk = maxLean <= MAX_TORSO_LEAN;

        String reason = null;
        if (!hipMoved) {
            reason = "髋部没有下蹲，只有膝盖前移";
        } else if (!leanOk) {
            reason = "上身过度前倾";
        } else if (!deepEnough) {
            reason = "下蹲深度不足";
        }

        if (!hipMoved) {
            phaseText = "站直";
            return; // 只抖了膝盖，不计数
        }
        if (!leanOk || !deepEnough) {
            if (!clearlyMoved && !leanOk) {
                phaseText = "站直";
                return;
            }
            badReps++;
            formErrors++;
        } else {
            goodReps++;
        }

        phaseText = "站直";
        hint = reason == null ? "" : reason;
        if (listener != null) {
            listener.onRep(new RepEvent(reason == null, reason, durationMs,
                    goodReps, badReps, minKnee, maxLean));
        }
    }

    private String depthHint(float lean, float travel, float minKnee) {
        if (lean > MAX_TORSO_LEAN) {
            return "上身太前倾，挺胸收腹";
        }
        if (travel < MIN_HIP_TRAVEL) {
            return "臀部再往下坐一些";
        }
        if (minKnee > DEPTH_ANGLE && travel < HIP_TRAVEL_DEEP) {
            return "再蹲深一点";
        }
        return "";
    }

    private void handleDropout(long now) {
        if (lastValidMs < 0) {
            phaseText = "未检测到人体";
            hint = "请正对或侧对镜头站直，全身入镜";
            return;
        }
        if (now - lastValidMs > DROPOUT_MS) {
            dropPhase();
            phaseText = "未检测到人体";
            hint = "请让全身进入画面";
        } else {
            phaseText = "识别不稳定";
            hint = "保持动作，稍等恢复";
        }
    }

    private void dropPhase() {
        downPhase = false;
        repMinKnee = Float.NaN;
        repMaxLean = 0f;
        repMaxTravel = 0f;
    }

    private void hidePartial() {
        phaseText = "请让腿部完整入镜";
        hint = "";
    }

    @Override
    public String getScoreText() {
        return goodReps + " 次";
    }

    @Override
    public String getStatusText() {
        return hint == null || hint.length() == 0 ? phaseText : phaseText + " · " + hint;
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

    @Override
    public int getGoodReps() {
        return goodReps;
    }

    @Override
    public int getBadReps() {
        return badReps;
    }

    @Override
    public int getFormErrors() {
        return formErrors;
    }

    @Override
    public float getDepthProgress() {
        if (Float.isNaN(smoothedKnee)) {
            return 0f;
        }
        float progress = (STAND_ANGLE - smoothedKnee) / (STAND_ANGLE - DEPTH_ANGLE);
        return Math.max(0f, Math.min(1f, progress));
    }

    /** 髋部相对站立时的下降比例（调试/展示用）。 */
    public float getHipTravelRatio() {
        return lastHipTravel;
    }
}
