package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.BodyMetrics;
import com.zxw.fitness.pose.PoseMath;

import java.util.Locale;

/**
 * 引体向上计数器。
 *
 * <p>没有可用的横杆检测，因此用"悬挂姿态 → 拉起"的状态机：</p>
 * <ul>
 *     <li>悬挂：双手在肩上方（手腕 y 明显小于肩）、手臂接近伸直（肘角 &gt; {@link #HANG_ANGLE}）；</li>
 *     <li>拉起：肘角小于 {@link #PULL_ANGLE}，同时肩部相对手腕上升了
 *     {@link #MIN_BODY_RISE} × 躯干长度以上（排除只是弯肘）；</li>
 *     <li>回到悬挂位计一次；拉起幅度不足（只到半程）记不标准；</li>
 *     <li>需要镜头能拍到全身（含双手），否则提示。</li>
 * </ul>
 */
public class PullUpCounter implements RepAnalyzer {

    /** 悬挂时手臂伸直的角度。 */
    private static final float HANG_ANGLE = 150f;
    /** 拉起到位（下巴接近/超过杆）的角度。 */
    private static final float PULL_ANGLE = 95f;
    /** 半程角度：到这才算"做了但不到位"。 */
    private static final float PARTIAL_ANGLE = 118f;
    /** 开始拉起的角度。 */
    private static final float START_PULL_ANGLE = 145f;
    /** 身体相对手腕的最小上升量（×躯干长度）。 */
    private static final float MIN_BODY_RISE = 0.10f;
    /** 手腕必须比肩高多少（比例量）才算悬挂在杆上。 */
    private static final float MIN_WRIST_ABOVE = 0.15f;
    private static final long MIN_REP_MS = 400;
    private static final long DROPOUT_MS = 600;
    private static final float SMOOTHING = 0.5f;

    private final RepListener listener;

    private float smoothedElbow = Float.NaN;
    /** 悬挂时肩-腕垂直距离基准（自动校准）。 */
    private float hangReach = Float.NaN;

    private boolean pullPhase;
    private long pullStartMs;
    private float repMinElbow;
    private float repMaxRise;

    private int goodReps;
    private int badReps;
    private int formErrors;
    private String hint = "";
    private String phaseText = "等待开始";
    private long lastValidMs = -1;

    public PullUpCounter(RepListener listener) {
        this.listener = listener;
    }

    @Override
    public void reset() {
        smoothedElbow = Float.NaN;
        hangReach = Float.NaN;
        pullPhase = false;
        pullStartMs = 0;
        repMinElbow = Float.NaN;
        repMaxRise = 0f;
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
            hint = "请让身体与双手都进入画面";
            handleDropout(now);
            return;
        }
        lastValidMs = now;

        float elbow = PoseMath.averageAngle(frame,
                PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST);
        if (Float.isNaN(elbow) || Float.isNaN(metrics.wristDropRatio)) {
            phaseText = "请让手臂完整入镜";
            hint = "镜头需要拍到杆与双手";
            return;
        }
        smoothedElbow = Float.isNaN(smoothedElbow)
                ? elbow
                : smoothedElbow + SMOOTHING * (elbow - smoothedElbow);
        // 状态切换用原始肘角（平滑会滞后，快速动作容易漏计），平滑值只用于界面进度
        float decisionElbow = elbow;

        // 悬垂时手腕高于肩：wristDropRatio 为负
        boolean hanging = -metrics.wristDropRatio >= MIN_WRIST_ABOVE;
        if (!hanging) {
            dropPhase();
            phaseText = "未检测到悬挂";
            hint = "双手握住横杆、身体完全悬空";
            return;
        }

        // 校准悬挂时的"肩-腕"距离（手臂伸直时的最大伸展）
        if (decisionElbow >= HANG_ANGLE) {
            hangReach = Float.isNaN(hangReach)
                    ? -metrics.wristDropRatio
                    : Math.max(hangReach, -metrics.wristDropRatio);
        }
        // wristDropRatio 与 hangReach 都已经是"相对躯干长度"的比例，直接相减即为身体上升比例
        float rise = Float.isNaN(hangReach)
                ? 0f
                : hangReach - (-metrics.wristDropRatio);

        if (!pullPhase) {
            if (decisionElbow < START_PULL_ANGLE) {
                pullPhase = true;
                pullStartMs = now;
                repMinElbow = elbow;
                repMaxRise = rise;
            } else {
                phaseText = "悬垂（可以开始拉）";
                hint = "";
                return;
            }
        } else {
            repMinElbow = Math.min(repMinElbow, elbow);
            repMaxRise = Math.max(repMaxRise, rise);
        }

        if (decisionElbow >= HANG_ANGLE) {
            if (pullPhase) {
                finishRep(now - pullStartMs);
            }
            phaseText = "悬垂";
        } else {
            phaseText = "拉起中";
            if (rise < MIN_BODY_RISE) {
                hint = "身体没有上去，注意收紧背部";
            } else {
                hint = "";
            }
        }
    }

    private void finishRep(long durationMs) {
        float minElbow = repMinElbow;
        float rise = repMaxRise;
        pullPhase = false;
        repMinElbow = Float.NaN;
        repMaxRise = 0f;

        if (durationMs < MIN_REP_MS) {
            phaseText = "悬垂";
            return;
        }

        boolean deepEnough = minElbow <= PULL_ANGLE;
        boolean clearlyMoved = minElbow <= PARTIAL_ANGLE;
        boolean bodyRose = rise >= MIN_BODY_RISE;

        String reason = null;
        if (!bodyRose) {
            reason = "身体没有跟上（只弯了手臂）";
        } else if (!deepEnough) {
            reason = "拉起高度不足，下巴没过杆";
        }

        if (!bodyRose) {
            phaseText = "悬垂";
            return;
        }
        if (!deepEnough) {
            if (!clearlyMoved) {
                phaseText = "悬垂";
                return;
            }
            badReps++;
            formErrors++;
        } else {
            goodReps++;
        }

        phaseText = "悬垂";
        hint = reason == null ? "" : reason;
        if (listener != null) {
            listener.onRep(new RepEvent(reason == null, reason, durationMs,
                    goodReps, badReps, minElbow, rise));
        }
    }

    private void handleDropout(long now) {
        if (lastValidMs < 0) {
            phaseText = "未检测到人体";
            hint = "请让双手与身体都进入画面";
            return;
        }
        if (now - lastValidMs > DROPOUT_MS) {
            dropPhase();
            phaseText = "未检测到人体";
            hint = "请让双手与身体都进入画面";
        } else {
            phaseText = "识别不稳定";
            hint = "保持动作，稍等恢复";
        }
    }

    private void dropPhase() {
        pullPhase = false;
        repMinElbow = Float.NaN;
        repMaxRise = 0f;
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
        return String.format(Locale.US, "有效 %d · 不标准 %d · 提示 %d 次",
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
        if (Float.isNaN(smoothedElbow)) {
            return 0f;
        }
        float progress = (HANG_ANGLE - smoothedElbow) / (HANG_ANGLE - PULL_ANGLE);
        return Math.max(0f, Math.min(1f, progress));
    }

    /** 动作类型（供通用逻辑识别）。 */
    public ExerciseType getType() {
        return ExerciseType.PULLUP;
    }
}
