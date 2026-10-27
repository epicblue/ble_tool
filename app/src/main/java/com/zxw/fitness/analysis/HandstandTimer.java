package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.PoseMath;

import java.util.Locale;

/**
 * 倒立 / 靠墙倒立的坚持时间计时器。
 *
 * <p>判定思路：</p>
 * <ul>
 *     <li>身体倒置：肩膀在髋部下方（图片 y 更大）、双脚高于肩。</li>
 *     <li>躯干接近竖直：与竖直方向夹角小于设定阈值。</li>
 *     <li>稳定：最近一段时间内髋部横向漂移很小，晃动过大则不计时。</li>
 *     <li>进入稳定倒立 0.3 秒后开始计时；条件被破坏超过 0.7 秒的宽限期后结束这一组，
 *     并记录本组时长（保留最久的一组作为成绩）。</li>
 *     <li>靠墙倒立额外统计"贴墙"比例（双脚横向几乎不动视为贴墙支撑），
 *     并要求更高的竖直度。</li>
 * </ul>
 */
public class HandstandTimer implements ExerciseAnalyzer {

    public interface Listener {
        /** 开始一组倒立。 */
        void onHoldStart(int attemptIndex);

        /** 结束一组倒立，参数为本组持续时间（毫秒）。 */
        void onHoldEnd(int attemptIndex, long durationMs);
    }

    /** 允许的躯干倾斜角度（自由倒立更严格）。 */
    private static final float MAX_TILT_FREE = 25f;
    private static final float MAX_TILT_WALL = 32f;
    /** 髋部横向漂移上限（归一化坐标）。 */
    private static final float MAX_DRIFT_FREE = 0.05f;
    private static final float MAX_DRIFT_WALL = 0.08f;
    /** 贴墙判定的双脚横移上限。 */
    private static final float WALL_ANKLE_DRIFT = 0.06f;
    /** 稳定多久开始计时。 */
    private static final long ARM_MS = 300;
    /** 条件破坏后的宽限期。 */
    private static final long GRACE_MS = 700;
    /** 稳定性采样窗口。 */
    private static final int HISTORY = 10;

    private final ExerciseType type;
    private final Listener listener;
    private final float[] hipXHistory = new float[HISTORY];
    private final float[] ankleXHistory = new float[HISTORY];
    private int historyCount;
    private int historyIndex;

    private long holdStartMs = -1;
    private long candidateStartMs = -1;
    private long lastGoodMs = -1;
    private long currentHoldMs;
    private long bestHoldMs;
    private long totalHoldMs;
    private int attempts;
    private int wallFrames;
    private int holdFrames;
    private boolean inGrace;
    private String statusText = "等待开始";

    public HandstandTimer(ExerciseType type, Listener listener) {
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
        currentHoldMs = 0;
        bestHoldMs = 0;
        totalHoldMs = 0;
        attempts = 0;
        wallFrames = 0;
        holdFrames = 0;
        inGrace = false;
        statusText = "等待开始";
    }

    @Override
    public void process(PoseFrame frame) {
        if (frame == null || !frame.isVisible()) {
            statusText = "未检测到人体";
            handleBadFrame(frame == null ? 0 : frame.getTimestampMs());
            return;
        }
        long now = frame.getTimestampMs();

        boolean inverted = PoseMath.isInverted(frame);
        float tilt = PoseMath.torsoAngleFromVertical(frame);
        float hipX = PoseMath.midX(frame, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP);
        float ankleX = PoseMath.ankleMidX(frame);

        pushHistory(hipX, ankleX);
        float hipDrift = PoseMath.spread(hipXHistory, historyCount);
        float ankleDrift = PoseMath.spread(ankleXHistory, historyCount);

        float maxTilt = isWallType() ? MAX_TILT_WALL : MAX_TILT_FREE;
        float maxDrift = isWallType() ? MAX_DRIFT_WALL : MAX_DRIFT_FREE;

        boolean vertical = !Float.isNaN(tilt) && tilt <= maxTilt;
        boolean steady = historyCount < 3 || hipDrift <= maxDrift;
        boolean upsideDown = inverted && vertical;

        if (frame.isVisible() && ankleDrift <= WALL_ANKLE_DRIFT && !Float.isNaN(ankleX)) {
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

    private void handleBadFrame(long now) {
        candidateStartMs = -1;
        if (holdStartMs >= 0) {
            if (lastGoodMs >= 0 && now - lastGoodMs <= GRACE_MS) {
                inGrace = true;
            } else {
                finishHold();
            }
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

    private void pushHistory(float hipX, float ankleX) {
        if (Float.isNaN(hipX)) {
            return;
        }
        hipXHistory[historyIndex] = hipX;
        ankleXHistory[historyIndex] = Float.isNaN(ankleX) ? hipX : ankleX;
        historyIndex = (historyIndex + 1) % HISTORY;
        if (historyCount < HISTORY) {
            historyCount++;
        }
    }

    /** 当前这一组已经保持的时间（毫秒）。 */
    public long getCurrentHoldMs() {
        return holdStartMs < 0 ? 0 : Math.max(currentHoldMs, lastGoodMs - holdStartMs);
    }

    public long getBestHoldMs() {
        long live = getCurrentHoldMs();
        return Math.max(bestHoldMs, live);
    }

    public long getTotalHoldMs() {
        return totalHoldMs + (holdStartMs < 0 ? 0 : getCurrentHoldMs());
    }

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
        return String.format(Locale.US, "本组 %s · 共 %d 组 · 累计 %s",
                formatSeconds(getCurrentHoldMs()), attempts, formatSeconds(getTotalHoldMs()));
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
}
