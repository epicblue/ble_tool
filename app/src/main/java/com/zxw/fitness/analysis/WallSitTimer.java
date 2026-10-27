package com.zxw.fitness.analysis;

import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.BodyMetrics;
import com.zxw.fitness.pose.PoseMath;

import java.util.Locale;

/**
 * 靠墙蹲（wall sit）计时器。
 *
 * <p>判定要点：</p>
 * <ul>
 *     <li><b>坐姿</b>：膝角（髋-膝-踝）落在 {@link #MIN_KNEE}~{@link #MAX_KNEE} 之间，
 *     也就是大腿接近水平、小腿竖直；</li>
 *     <li><b>大腿水平</b>：髋部与膝盖的高度差 ÷ 躯干长度 ≤ {@link #MAX_THIGH_RISE}，
 *     站着半蹲时髋明显高于膝，不计时；</li>
 *     <li><b>背靠墙</b>：躯干接近竖直（与竖直方向夹角 ≤ {@link #MAX_TORSO_LEAN}）；</li>
 *     <li>保持 {@link #ARM_MS} 后开始计时；条件破坏有 {@link #GRACE_MS} 宽限期，
 *     短暂晃动不打断；站起来（膝角变大）超过宽限期即结束本组。</li>
 * </ul>
 * <p>同时统计膝角抖动，输出"稳定度"评分。</p>
 */
public class WallSitTimer implements HoldAnalyzer {

    /** 有效坐姿的膝角下限（更小说明蹲得过深、接近跪姿）。 */
    private static final float MIN_KNEE = 60f;
    /** 有效坐姿的膝角上限（更大说明站起来了）。 */
    private static final float MAX_KNEE = 110f;
    /** 髋膝高度差 ÷ 躯干长度的上限，超过说明大腿没有水平。 */
    private static final float MAX_THIGH_RISE = 0.30f;
    /** 躯干与竖直方向夹角上限（背靠墙）。 */
    private static final float MAX_TORSO_LEAN = 30f;
    /** 稳定多久开始计时。 */
    private static final long ARM_MS = 400;
    /** 条件破坏后的宽限期。 */
    private static final long GRACE_MS = 800;
    /** 允许的人体丢失时长。 */
    private static final long DROPOUT_MS = 600;
    /** 膝角标准差映射到稳定度时的参考值（度）。 */
    private static final float KNEE_STD_REFERENCE = 20f;

    private final HoldListener listener;

    private long holdStartMs = -1;
    private long candidateStartMs = -1;
    private long lastGoodMs = -1;
    private long lastValidMs = -1;
    private long currentHoldMs;
    private long bestHoldMs;
    private long totalHoldMs;
    private int attempts;
    private int goodFrames;
    private int badFrames;
    private float kneeSum;
    private float kneeSquareSum;
    private float lastKnee = Float.NaN;
    private String statusText = "等待开始";

    public WallSitTimer(HoldListener listener) {
        this.listener = listener;
    }

    @Override
    public void reset() {
        holdStartMs = -1;
        candidateStartMs = -1;
        lastGoodMs = -1;
        lastValidMs = -1;
        currentHoldMs = 0;
        bestHoldMs = 0;
        totalHoldMs = 0;
        attempts = 0;
        goodFrames = 0;
        badFrames = 0;
        kneeSum = 0f;
        kneeSquareSum = 0f;
        lastKnee = Float.NaN;
        statusText = "等待开始";
    }

    @Override
    public void process(PoseFrame frame) {
        long now = frame == null ? System.currentTimeMillis() : frame.getTimestampMs();
        if (frame == null || !frame.isVisible()) {
            interrupt(now);
            statusText = "未检测到人体";
            return;
        }
        BodyMetrics metrics = BodyMetrics.of(frame);
        if (!metrics.valid) {
            interrupt(now);
            statusText = "未完整识别到身体";
            return;
        }
        lastValidMs = now;

        float knee = PoseMath.averageAngle(frame,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE);
        lastKnee = knee;
        if (Float.isNaN(knee)) {
            interrupt(now);
            statusText = "请让腿部完整入镜";
            return;
        }

        float kneeMidY = PoseMath.midY(frame, PoseFrame.LEFT_KNEE, PoseFrame.RIGHT_KNEE);
        float thighRise = Float.isNaN(kneeMidY)
                ? 0f
                : Math.abs(metrics.hipY - kneeMidY) / Math.max(0.04f, metrics.bodyScale);
        float lean = metrics.torsoAngleFromVertical();
        if (Float.isNaN(lean)) {
            lean = 0f;
        }
        boolean hipBelowShoulder = metrics.hipY > metrics.shoulderY + 0.02f;

        boolean sitting = knee >= MIN_KNEE && knee <= MAX_KNEE
                && hipBelowShoulder
                && thighRise <= MAX_THIGH_RISE
                && lean <= MAX_TORSO_LEAN;

        if (sitting) {
            if (holdStartMs < 0) {
                if (candidateStartMs < 0) {
                    candidateStartMs = now;
                }
                if (now - candidateStartMs >= ARM_MS) {
                    holdStartMs = candidateStartMs;
                    attempts++;
                    if (listener != null) {
                        listener.onHoldStart(attempts);
                    }
                }
                statusText = "稳住，马上开始计时";
            } else {
                currentHoldMs = now - holdStartMs;
                lastGoodMs = now;
                goodFrames++;
                kneeSum += knee;
                kneeSquareSum += knee * knee;
                statusText = "保持中";
            }
            return;
        }

        // ---- 不合格帧：给出具体原因 ----
        candidateStartMs = -1;
        badFrames++;
        if (knee > MAX_KNEE) {
            statusText = "站起来了，坐回大腿水平位";
        } else if (thighRise > MAX_THIGH_RISE) {
            statusText = "臀部再往下坐，大腿要水平";
        } else if (lean > MAX_TORSO_LEAN) {
            statusText = "上身贴住墙，别前倾";
        } else {
            statusText = "姿势不合格";
        }
        if (holdStartMs >= 0) {
            if (lastGoodMs >= 0 && now - lastGoodMs <= GRACE_MS) {
                statusText = statusText + "（宽限期，快坐回去）";
            } else {
                finishHold();
            }
        }
    }

    private void interrupt(long now) {
        candidateStartMs = -1;
        if (holdStartMs >= 0 && (lastGoodMs < 0 || now - lastGoodMs > DROPOUT_MS)) {
            finishHold();
        }
    }

    private void finishHold() {
        long base = lastGoodMs >= 0 ? lastGoodMs : holdStartMs;
        long duration = Math.max(0, base - holdStartMs);
        if (duration > 0) {
            bestHoldMs = Math.max(bestHoldMs, duration);
            totalHoldMs += duration;
            currentHoldMs = duration;
            if (listener != null) {
                listener.onHoldEnd(attempts, duration);
            }
        }
        holdStartMs = -1;
        lastGoodMs = -1;
        candidateStartMs = -1;
        statusText = "已结束一组";
    }

    @Override
    public long getCurrentHoldMs() {
        if (holdStartMs < 0) {
            return 0;
        }
        long base = lastGoodMs >= 0 ? lastGoodMs : holdStartMs;
        return Math.max(currentHoldMs, base - holdStartMs);
    }

    @Override
    public long getBestHoldMs() {
        return Math.max(bestHoldMs, getCurrentHoldMs());
    }

    @Override
    public long getTotalHoldMs() {
        return totalHoldMs + (holdStartMs < 0 ? 0 : getCurrentHoldMs());
    }

    @Override
    public int getAttempts() {
        return attempts;
    }

    /** 稳定度：保持过程中膝角波动越小分数越高。 */
    @Override
    public float getStability() {
        if (goodFrames == 0) {
            return 0f;
        }
        float mean = kneeSum / goodFrames;
        float variance = Math.max(0f, kneeSquareSum / goodFrames - mean * mean);
        float std = (float) Math.sqrt(variance);
        return Math.max(0f, Math.min(1f, 1f - std / KNEE_STD_REFERENCE));
    }

    @Override
    public String getExtraText() {
        if (Float.isNaN(lastKnee)) {
            return "";
        }
        return String.format(Locale.US, "膝角 %d°", Math.round(lastKnee));
    }

    @Override
    public String getScoreText() {
        return formatSeconds(getBestHoldMs());
    }

    @Override
    public String getStatusText() {
        return statusText;
    }

    @Override
    public String getDetailText() {
        return String.format(Locale.US, "本组 %s · 共 %d 组 · 累计 %s · 稳定度 %d%%",
                formatSeconds(getCurrentHoldMs()), attempts, formatSeconds(getTotalHoldMs()),
                (int) (getStability() * 100));
    }

    @Override
    public boolean hasResult() {
        return bestHoldMs > 0 || (holdStartMs >= 0 && getCurrentHoldMs() > 0);
    }

    private static String formatSeconds(long ms) {
        return String.format(Locale.US, "%.1f 秒", ms / 1000.0);
    }
}
