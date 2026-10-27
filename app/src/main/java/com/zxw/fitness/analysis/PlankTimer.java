package com.zxw.fitness.analysis;

import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.BodyMetrics;

import java.util.Locale;

/**
 * 平板支撑计时器。
 *
 * <p>判定：小臂/手掌撑地，身体从肩到踝成一条直线并保持。</p>
 * <ul>
 *     <li>躯干接近水平，且手腕在肩下方（撑地），髋部离地；</li>
 *     <li>直线度用 {@link BodyMetrics#sagRatio}（与视角无关）：塌腰超过
 *     {@link #SAG_LIMIT} 视为塌腰，此时<b>不计时</b>并累加提醒次数；</li>
 *     <li>条件被破坏后有 {@link #GRACE_MS} 宽限期，恢复后继续累计；</li>
 *     <li>保持超过 {@link #ARM_MS} 才开始计时，避免"趴下又起来"被计入。</li>
 * </ul>
 */
public class PlankTimer implements HoldAnalyzer {

    /** 躯干与水平方向最大夹角（度）。 */
    private static final float MAX_TORSO_HORIZONTAL = 30f;
    /** 髋偏离肩-踝连线上限（×躯干长度）。 */
    private static final float SAG_LIMIT = 0.15f;
    /** 手腕至少要低于肩部多少（比例量）。 */
    private static final float MIN_WRIST_DROP = 0.05f;
    /** 髋部必须明显高于脚踝（避免站着被误判为平板）。 */
    private static final float MIN_HIP_LIFT = 0.02f;
    /** 稳定多久开始计时。 */
    private static final long ARM_MS = 500;
    /** 条件破坏后的宽限期。 */
    private static final long GRACE_MS = 800;
    /** 允许的人体丢失时长。 */
    private static final long DROPOUT_MS = 600;

    private final HoldListener listener;

    private long holdStartMs = -1;
    private long candidateStartMs = -1;
    private long lastGoodMs = -1;
    private long lastValidMs = -1;
    private long currentHoldMs;
    private long bestHoldMs;
    private long totalHoldMs;
    private int attempts;
    private int sagWarnings;
    private int goodFrames;
    private int sagFrames;
    private String statusText = "等待开始";

    public PlankTimer(HoldListener listener) {
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
        sagWarnings = 0;
        goodFrames = 0;
        sagFrames = 0;
        statusText = "等待开始";
    }

    @Override
    public void process(PoseFrame frame) {
        long now = frame == null ? System.currentTimeMillis() : frame.getTimestampMs();

        if (frame == null || !frame.isVisible()) {
            if (lastValidMs >= 0 && now - lastValidMs > DROPOUT_MS) {
                interrupt(now);
                statusText = "未检测到人体";
            } else {
                statusText = "识别不稳定";
            }
            return;
        }
        BodyMetrics metrics = BodyMetrics.of(frame);
        if (!metrics.valid) {
            statusText = "未完整识别到身体";
            interrupt(now);
            return;
        }
        lastValidMs = now;

        float torsoHorizontal = metrics.torsoAngleFromHorizontal();
        boolean prone = !Float.isNaN(torsoHorizontal) && torsoHorizontal < MAX_TORSO_HORIZONTAL;
        boolean handsDown = !Float.isNaN(metrics.wristDropRatio)
                && metrics.wristDropRatio > MIN_WRIST_DROP;
        boolean hipLifted = metrics.hasAnkle
                && metrics.hipY < metrics.ankleY - MIN_HIP_LIFT;
        boolean aligned = Float.isNaN(metrics.sagRatio) || metrics.sagRatio <= SAG_LIMIT;

        if (prone && handsDown && !aligned) {
            // 塌腰/撅臀：这是平板支撑最主要的问题，优先提示并停止计时
            sagFrames++;
            if (sagFrames % 15 == 1) {
                sagWarnings++;
            }
            statusText = "塌腰了，收紧腹部把髋抬起来";
            candidateStartMs = -1;
            if (holdStartMs >= 0) {
                if (lastGoodMs >= 0 && now - lastGoodMs <= GRACE_MS) {
                    statusText = statusText + "（宽限期，快调整）";
                } else {
                    finishHold();
                }
            }
            return;
        }

        if (prone && handsDown && hipLifted && aligned) {
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
                statusText = "撑住，马上开始计时";
            } else {
                currentHoldMs = now - holdStartMs;
                lastGoodMs = now;
                goodFrames++;
                statusText = "保持中";
            }
            sagFrames += 0;
            return;
        }

        // 无效帧：分类提示
        if (!prone) {
            statusText = "身体没有放平（需要肩-髋-脚成一条线）";
        } else if (!handsDown) {
            statusText = "小臂要撑在肩的正下方";
        } else if (!hipLifted) {
            statusText = "髋部离地，别塌下去";
        } else {
            statusText = "姿势不合格";
        }

        candidateStartMs = -1;
        if (holdStartMs >= 0) {
            if (lastGoodMs >= 0 && now - lastGoodMs <= GRACE_MS) {
                statusText = statusText + "（宽限期，快调整）";
            } else {
                finishHold();
            }
        }
    }

    private void interrupt(long now) {
        candidateStartMs = -1;
        if (holdStartMs >= 0 && (lastGoodMs < 0 || now - lastGoodMs > GRACE_MS)) {
            finishHold();
        }
    }

    private void finishHold() {
        long duration = Math.max(0, lastGoodMs - holdStartMs);
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
        long base = lastGoodMs > 0 ? lastGoodMs : holdStartMs;
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

    @Override
    public float getStability() {
        int total = goodFrames + sagFrames;
        if (total == 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, goodFrames / (float) total));
    }

    @Override
    public String getExtraText() {
        return sagWarnings > 0 ? "塌腰提醒 " + sagWarnings + " 次" : "";
    }

    @Override
    public String getScoreText() {
        return String.format(Locale.US, "%.1f 秒", getBestHoldMs() / 1000.0);
    }

    @Override
    public String getStatusText() {
        return statusText;
    }

    @Override
    public String getDetailText() {
        return String.format(Locale.US, "本组 %.1f 秒 · 共 %d 组 · 稳定度 %d%%",
                getCurrentHoldMs() / 1000.0, attempts, (int) (getStability() * 100));
    }

    @Override
    public boolean hasResult() {
        return bestHoldMs > 0 || (holdStartMs >= 0 && getCurrentHoldMs() > 0);
    }

    /** 是否正在计时。 */
    public boolean isHolding() {
        return holdStartMs >= 0;
    }

    public int getSagWarnings() {
        return sagWarnings;
    }

    /** 便于界面直接展示的静态格式化。 */
    public static String formatSeconds(long ms) {
        return String.format(Locale.US, "%.1f 秒", ms / 1000.0);
    }
}
