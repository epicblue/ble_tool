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
            return "开始跑步后自动计步 · 步幅识别基于左右脚交替";
        }
        return String.format(Locale.US, "步频 %.0f 步/分 · 共 %d 步", getCadence(), steps);
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
