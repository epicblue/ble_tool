package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.BodyMetrics;
import com.zxw.fitness.pose.PoseMath;

import java.util.Locale;

/**
 * 跑步计步（用摄像头数步数）。
 *
 * <p>原理：跑步/原地跑时左右脚交替离地。把"右脚踝高度 − 左脚踝高度"按躯干长度归一化，
 * 得到一个围绕 0 上下摆动的信号，符号每翻转一次即一步。</p>
 *
 * <p>为避免误计，加了三道约束：</p>
 * <ol>
 *     <li><b>迟滞</b>：信号幅度必须超过 {@link #SIGNAL_ON}（×躯干长度）才算有效翻转，
 *     站着不动或轻微晃动不会触发；</li>
 *     <li><b>步频下限</b>：两步间隔 ≥ {@link #MIN_STEP_MS}（约 333 步/分），过滤抖动；</li>
 *     <li><b>步频上限</b>：间隔 &gt; {@link #MAX_STEP_MS} 视为已经停下，重新开始计数但不补步。</li>
 * </ol>
 *
 * <p>要求：全身入镜（尤其双脚），原地跑或面向/侧对镜头跑都可以。</p>
 */
public class RunStepCounter implements RepAnalyzer {

    /** 触发一步所需的左右脚高度差（×躯干长度）。 */
    private static final float SIGNAL_ON = 0.16f;
    /** 两步之间的最短间隔（毫秒）。 */
    private static final long MIN_STEP_MS = 180;
    /** 超过该间隔视为停止跑步。 */
    private static final long MAX_STEP_MS = 1600;
    /** 允许的人体丢失时长。 */
    private static final long DROPOUT_MS = 700;
    /** 计算平均步频时保留的最近间隔数。 */
    private static final int INTERVAL_WINDOW = 10;
    /** 一侧至少要连续保持几帧，翻转才算一步（滤掉高频抖动）。 */
    private static final int MIN_STABLE_FRAMES = 2;
    /** 步频 ≥ 该值直接判为跑步（步/分）。 */
    private static final float RUN_CADENCE = 150f;
    /** 步频 ≤ 该值直接判为走路（步/分）。 */
    private static final float WALK_CADENCE = 120f;
    /** 步频在两者之间时，用躯干上下起伏（×躯干长度）区分：跑步腾空更明显。 */
    private static final float HIP_BOB_RUN = 0.05f;
    /** 起伏统计窗口（帧）。 */
    private static final int BOB_WINDOW = 20;

    private final RepListener listener;

    private final long[] intervals = new long[INTERVAL_WINDOW];
    private int intervalCount;
    private int intervalIndex;

    private int steps;
    private int lastSign;
    /** 当前这一侧已经连续出现的帧数。 */
    private int stableFrames;
    /** 上一侧的持续帧数，用于判断翻转是否可信。 */
    private int heldFrames;
    private long lastStepMs = -1;
    private long lastValidMs = -1;
    private float maxSignal;
    private float lastSignal;
    private float lastScale = 0.04f;
    private final float[] hipHistory = new float[BOB_WINDOW];
    private int hipCount;
    private int hipIndex;
    private String statusText = "等待开始";

    public RunStepCounter(RepListener listener) {
        this.listener = listener;
    }

    @Override
    public void reset() {
        intervalCount = 0;
        intervalIndex = 0;
        steps = 0;
        lastSign = 0;
        stableFrames = 0;
        heldFrames = 0;
        lastStepMs = -1;
        lastValidMs = -1;
        maxSignal = 0f;
        lastSignal = 0f;
        lastScale = 0.04f;
        hipCount = 0;
        hipIndex = 0;
        statusText = "等待开始";
    }

    @Override
    public void process(PoseFrame frame) {
        long now = frame == null ? System.currentTimeMillis() : frame.getTimestampMs();
        if (frame == null || !frame.isVisible()) {
            if (lastValidMs >= 0 && now - lastValidMs > DROPOUT_MS) {
                lastSign = 0;
                statusText = "未检测到人体";
            } else {
                statusText = "识别不稳定";
            }
            return;
        }
        BodyMetrics metrics = BodyMetrics.of(frame);
        if (!metrics.valid || !metrics.hasAnkle) {
            statusText = "请让全身入镜（双脚要看得见）";
            return;
        }
        if (!PoseMath.isReliable(frame, PoseFrame.LEFT_ANKLE)
                || !PoseMath.isReliable(frame, PoseFrame.RIGHT_ANKLE)) {
            statusText = "双脚需要同时入镜";
            return;
        }
        if (metrics.hipY <= metrics.shoulderY + 0.02f) {
            statusText = "请站直，让髋部在肩部下方";
            return;
        }
        lastValidMs = now;

        float scale = Math.max(0.04f, metrics.bodyScale);
        lastScale = scale;
        pushHip(metrics.hipY);
        float signal = (frame.getY(PoseFrame.RIGHT_ANKLE) - frame.getY(PoseFrame.LEFT_ANKLE)) / scale;
        lastSignal = signal;
        maxSignal = Math.max(maxSignal, Math.abs(signal));

        int sign = 0;
        if (signal >= SIGNAL_ON) {
            sign = 1;
        } else if (signal <= -SIGNAL_ON) {
            sign = -1;
        }

        if (sign == 0) {
            // 回到中间位：把当前侧视为结束，下次翻转需要重新积累帧数
            heldFrames = stableFrames;
            stableFrames = 0;
            statusText = "跑起来（左右脚交替抬起）";
            return;
        }

        if (sign == lastSign) {
            stableFrames++;
            statusText = "跑步中";
            return;
        }

        // 发生翻转：上一侧必须"站稳"过至少 MIN_STABLE_FRAMES 帧，否则视为抖动
        int previousFrames = Math.max(heldFrames, stableFrames);
        boolean trustworthy = lastSign == 0 || previousFrames >= MIN_STABLE_FRAMES;
        if (trustworthy) {
            long since = lastStepMs < 0 ? Long.MAX_VALUE : now - lastStepMs;
            if (since >= MIN_STEP_MS) {
                if (since <= MAX_STEP_MS) {
                    steps++;
                    addInterval(since);
                    if (listener != null) {
                        listener.onRep(new RepEvent(true, null, since, steps, 0, 0f, 0f));
                    }
                }
                lastStepMs = now;
            }
        }
        heldFrames = stableFrames;
        stableFrames = 1;
        lastSign = sign;
        statusText = "跑步中";
    }

    private void addInterval(long interval) {
        intervals[intervalIndex] = interval;
        intervalIndex = (intervalIndex + 1) % INTERVAL_WINDOW;
        if (intervalCount < INTERVAL_WINDOW) {
            intervalCount++;
        }
    }

    private void pushHip(float hipY) {
        hipHistory[hipIndex] = hipY;
        hipIndex = (hipIndex + 1) % BOB_WINDOW;
        if (hipCount < BOB_WINDOW) {
            hipCount++;
        }
    }

    /** 躯干上下起伏（峰峰值 ÷ 躯干长度）；帧数不足返回 NaN。 */
    public float getHipBob() {
        if (hipCount < 4) {
            return Float.NaN;
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int i = 0; i < hipCount; i++) {
            min = Math.min(min, hipHistory[i]);
            max = Math.max(max, hipHistory[i]);
        }
        return (max - min) / Math.max(0.04f, lastScale);
    }

    /**
     * 走 / 跑分类：步频为主，模糊区间看躯干起伏。
     *
     * <p>为什么需要：脚踝交替离地的模式走和跑几乎一样，只看信号的话
     * 90 步/分和 160 步/分都会被当成"跑步计步"。跑步有明显的腾空与躯干起伏，
     * 用步频 + 起伏可以在不增加摄像头要求的前提下区分。</p>
     *
     * @return {@code "RUN"} / {@code "WALK"} / {@code "UNKNOWN"}
     */
    public String getGait() {
        if (steps < 2) {
            return "UNKNOWN";
        }
        float cadence = getCadence();
        if (cadence <= 0f) {
            return "UNKNOWN";
        }
        if (cadence >= RUN_CADENCE) {
            return "RUN";
        }
        if (cadence <= WALK_CADENCE) {
            return "WALK";
        }
        float bob = getHipBob();
        if (Float.isNaN(bob)) {
            return cadence >= (RUN_CADENCE + WALK_CADENCE) / 2f ? "RUN" : "WALK";
        }
        return bob >= HIP_BOB_RUN ? "RUN" : "WALK";
    }

    /** 界面用语：跑步 / 走路 / 空字符串。 */
    public String getGaitText() {
        String gait = getGait();
        if ("RUN".equals(gait)) {
            return "跑步";
        }
        if ("WALK".equals(gait)) {
            return "走路";
        }
        return "";
    }

    /** @return 平均步频（步/分），无数据时为 0。 */
    public float getCadence() {
        if (intervalCount == 0) {
            return 0f;
        }
        long sum = 0;
        for (int i = 0; i < intervalCount; i++) {
            sum += intervals[i];
        }
        float avg = sum / (float) intervalCount;
        return avg > 0 ? 60000f / avg : 0f;
    }

    public float getMaxSignal() {
        return maxSignal;
    }

    public float getLastSignal() {
        return lastSignal;
    }

    @Override
    public int getGoodReps() {
        return steps;
    }

    @Override
    public int getBadReps() {
        return 0;
    }

    @Override
    public int getFormErrors() {
        return 0;
    }

    @Override
    public float getDepthProgress() {
        // 用"当前摆动幅度"作为进度条，帮助用户确认识别是否有效
        if (maxSignal <= 0f) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, Math.abs(lastSignal) / (SIGNAL_ON * 2f)));
    }

    @Override
    public String getScoreText() {
        return steps + " 步";
    }

    @Override
    public String getStatusText() {
        return statusText;
    }

    @Override
    public String getDetailText() {
        if (steps == 0) {
            return "跑起来（或原地踏步）自动计步 · 需拍到双脚";
        }
        String gait = getGaitText();
        String prefix = gait.length() == 0 ? "" : gait + " · ";
        return String.format(Locale.US, "%s步频 %.0f 步/分 · 共 %d 步", prefix, getCadence(), steps);
    }

    @Override
    public boolean hasResult() {
        return steps > 0;
    }

    /** 便于界面区分动作类型。 */
    public ExerciseType getType() {
        return ExerciseType.RUNNING;
    }
}
