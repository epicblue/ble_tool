package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.BodyMetrics;

import java.util.Locale;

/**
 * 倒立 / 靠墙倒立的坚持时间计时器。
 *
 * <p>判定思路：</p>
 * <ul>
 *     <li>身体倒置：肩膀在髋部下方（图片 y 更大）、双脚高于肩。</li>
 *     <li>躯干接近竖直：与竖直方向夹角小于设定阈值。</li>
 *     <li>稳定：最近一段时间的<b>髋部横向漂移 / 躯干长度</b> 很小（与镜头距离无关），
 *     晃动过大则不计时。</li>
 *     <li>进入稳定倒立 {@link #ARM_MS} 后开始计时；条件被破坏超过 {@link #GRACE_MS}
 *     的宽限期后结束这一组，并记录本组时长（保留最久的一组作为成绩）。</li>
 *     <li>靠墙倒立额外统计"贴墙"比例（双脚横向几乎不动视为贴墙支撑），
 *     并要求更高的竖直度。</li>
 * </ul>
 */
public class HandstandTimer implements HoldAnalyzer {

    /** 允许的躯干倾斜角度（自由倒立更严格）。 */
    private static final float MAX_TILT_FREE = 25f;
    private static final float MAX_TILT_WALL = 32f;
    /** 髋部横向漂移上限（×躯干长度）。 */
    private static final float MAX_DRIFT_FREE = 0.18f;
    private static final float MAX_DRIFT_WALL = 0.30f;
    /** 贴墙判定的双脚横移上限（×躯干长度）。 */
    private static final float WALL_ANKLE_DRIFT = 0.25f;
    /** 稳定多久开始计时。 */
    private static final long ARM_MS = 300;
    /** 条件破坏后的宽限期。 */
    private static final long GRACE_MS = 700;
    /** 稳定性采样窗口。 */
    private static final int HISTORY = 12;

    private final ExerciseType type;
    private final HoldListener listener;

    private final float[] hipXHistory = new float[HISTORY];
    private final float[] ankleXHistory = new float[HISTORY];
    private final float[] tiltHistory = new float[HISTORY];
    private final float[] scaleHistory = new float[HISTORY];
    private int historyCount;
    private int historyIndex;

    private long holdStartMs = -1;
    private long candidateStartMs = -1;
    private long lastGoodMs = -1;
    private long lastValidMs = -1;
    private long currentHoldMs;
    private long bestHoldMs;
    private long totalHoldMs;
    private int attempts;
    private int wallFrames;
    private int holdFrames;
    private float tiltSum;
    private boolean inGrace;
    private String statusText = "等待开始";

    public HandstandTimer(ExerciseType type, HoldListener listener) {
        this.type = type == null ? ExerciseType.HANDSTAND : type;
        this.listener = listener;
    }

    private boolean isWallType() {
        return type == ExerciseType.WALL_HANDSTAND;
    }

    @Override
    public void reset() {
        historyCount = 0;
        historyIndex = 0;
        holdStartMs = -1;
        candidateStartMs = -1;
        lastGoodMs = -1;
        lastValidMs = -1;
        currentHoldMs = 0;
        bestHoldMs = 0;
        totalHoldMs = 0;
        attempts = 0;
        wallFrames = 0;
        holdFrames = 0;
        tiltSum = 0f;
        inGrace = false;
        statusText = "等待开始";
    }

    @Override
    public void process(PoseFrame frame) {
        long now = frame == null ? System.currentTimeMillis() : frame.getTimestampMs();
        if (frame == null || !frame.isVisible()) {
            handleBadFrame(now, "未检测到人体");
            return;
        }
        BodyMetrics m = BodyMetrics.of(frame);
        if (!m.valid) {
            handleBadFrame(now, "未完整识别到身体");
            return;
        }
        lastValidMs = now;

        boolean inverted = m.isInverted();
        float tilt = m.torsoAngleFromVertical();
        float hipX = m.hipX;
        float ankleX = m.hasAnkle ? m.ankleX : Float.NaN;
        float scale = m.bodyScale;

        pushHistory(hipX, ankleX, tilt, scale);
        float hipDrift = PoseMathSpread.ratio(spread(hipXHistory, historyCount),
                average(scaleHistory, historyCount));
        float ankleDrift = PoseMathSpread.ratio(spread(ankleXHistory, historyCount),
                average(scaleHistory, historyCount));

        float maxTilt = isWallType() ? MAX_TILT_WALL : MAX_TILT_FREE;
        float maxDrift = isWallType() ? MAX_DRIFT_WALL : MAX_DRIFT_FREE;

        boolean vertical = !Float.isNaN(tilt) && tilt <= maxTilt;
        boolean steady = historyCount < 3 || hipDrift <= maxDrift;
        boolean upsideDown = inverted && vertical;

        if (!Float.isNaN(ankleX) && ankleDrift <= WALL_ANKLE_DRIFT) {
            wallFrames++;
        }

        if (upsideDown && steady) {
            if (holdStartMs < 0) {
                if (candidateStartMs < 0) {
                    candidateStartMs = now;
                }
                if (now - candidateStartMs >= ARM_MS) {
                    holdStartMs = candidateStartMs;
                    attempts++;
                    inGrace = false;
                    if (listener != null) {
                        listener.onHoldStart(attempts);
                    }
                }
                statusText = "稳住，马上开始计时";
            } else {
                currentHoldMs = now - holdStartMs;
                lastGoodMs = now;
                holdFrames++;
                tiltSum += tilt;
                inGrace = false;
                statusText = "保持中";
            }
        } else {
            candidateStartMs = -1;
            if (holdStartMs >= 0) {
                if (now - lastGoodMs <= GRACE_MS) {
                    inGrace = true;
                    currentHoldMs = lastGoodMs - holdStartMs;
                    statusText = "快要掉了，坚持住";
                } else {
                    finishHold();
                }
            } else {
                inGrace = false;
                if (!inverted) {
                    statusText = "未检测到倒立（双脚需高于髋部）";
                } else if (!vertical) {
                    statusText = isWallType()
                            ? "身体不够竖直，靠近墙借力"
                            : "身体倾斜较大，收紧核心";
                } else {
                    statusText = "晃动太大，先稳定下来";
                }
            }
        }
    }

    private void handleBadFrame(long now, String message) {
        candidateStartMs = -1;
        if (holdStartMs >= 0) {
            if (lastGoodMs >= 0 && now - lastGoodMs <= GRACE_MS) {
                inGrace = true;
                statusText = "识别不稳定";
            } else {
                finishHold();
                statusText = message;
            }
        } else if (lastValidMs < 0 || now - lastValidMs > 600) {
            statusText = message;
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
        inGrace = false;
        statusText = "已结束一组";
    }

    private void pushHistory(float hipX, float ankleX, float tilt, float scale) {
        if (Float.isNaN(hipX)) {
            return;
        }
        hipXHistory[historyIndex] = hipX;
        ankleXHistory[historyIndex] = Float.isNaN(ankleX) ? hipX : ankleX;
        tiltHistory[historyIndex] = Float.isNaN(tilt) ? 0f : tilt;
        scaleHistory[historyIndex] = Math.max(0.04f, scale);
        historyIndex = (historyIndex + 1) % HISTORY;
        if (historyCount < HISTORY) {
            historyCount++;
        }
    }

    private static float spread(float[] values, int count) {
        if (count <= 1) {
            return 0f;
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < count && i < values.length; i++) {
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
        }
        return max - min;
    }

    private static float average(float[] values, int count) {
        if (count <= 0) {
            return 1f;
        }
        float sum = 0f;
        int n = Math.min(count, values.length);
        for (int i = 0; i < n; i++) {
            sum += values[i];
        }
        return sum / n;
    }

    @Override
    public long getCurrentHoldMs() {
        return holdStartMs < 0 ? 0 : Math.max(currentHoldMs, lastGoodMs - holdStartMs);
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

    public boolean isHolding() {
        return holdStartMs >= 0;
    }

    /** 贴墙比例 0~1（仅靠墙倒立有意义）。 */
    public float getWallRatio() {
        if (holdFrames == 0) {
            return 0f;
        }
        return Math.min(1f, wallFrames / (float) holdFrames);
    }

    /**
     * 稳定性评分 0~1：保持过程中晃动越小、越竖直，分数越高。
     */
    @Override
    public float getStability() {
        if (holdFrames == 0 || historyCount < 3) {
            return holdFrames > 0 ? 0.6f : 0f;
        }
        float maxDrift = isWallType() ? MAX_DRIFT_WALL : MAX_DRIFT_FREE;
        float drift = PoseMathSpread.ratio(spread(hipXHistory, historyCount),
                average(scaleHistory, historyCount));
        float driftScore = 1f - Math.min(1f, drift / Math.max(0.01f, maxDrift));
        float avgTilt = tiltSum / Math.max(1, holdFrames);
        float maxTilt = isWallType() ? MAX_TILT_WALL : MAX_TILT_FREE;
        float tiltScore = 1f - Math.min(1f, avgTilt / Math.max(1f, maxTilt));
        return Math.max(0f, Math.min(1f, driftScore * 0.6f + tiltScore * 0.4f));
    }

    @Override
    public String getExtraText() {
        if (isWallType()) {
            return String.format(Locale.US, "贴墙 %d%%", (int) (getWallRatio() * 100));
        }
        return "";
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

    public static String formatSeconds(long ms) {
        return String.format(Locale.US, "%.1f 秒", ms / 1000.0);
    }

    /** 当前是否处于"快要结束"的宽限期（用于界面提示）。 */
    public boolean isInGrace() {
        return inGrace;
    }

    /** 小工具：把像素漂移换算成相对躯干的比值。 */
    private static final class PoseMathSpread {
        static float ratio(float drift, float scale) {
            return drift / Math.max(0.04f, scale);
        }
    }
}
