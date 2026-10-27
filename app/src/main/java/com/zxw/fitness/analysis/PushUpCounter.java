package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.BodyMetrics;
import com.zxw.fitness.pose.PoseMath;

import java.util.Locale;

/**
 * 俯卧撑计数器（俯卧撑 / 折凳俯卧撑 / 不标准俯卧撑共用）。
 *
 * <p>相比最初的版本，判定做了三点增强：</p>
 * <ol>
 *     <li><b>深度用双信号</b>：侧对镜头时用肩-肘-腕夹角；正对/斜对镜头（关节角在图像上被压缩）
 *     或手臂关键点不可靠时，自动改用"手腕比肩低多少 / 身体尺度"的比例量。
 *     两者都能用时取更灵敏的那个。</li>
 *     <li><b>身体直线度用比例量</b>：{@link BodyMetrics#sagRatio}（髋偏离肩-踝连线的距离 / 躯干长度），
 *     与机位、人体距离无关；关节角只作为侧对镜头时的辅助判据。</li>
 *     <li><b>抗丢帧</b>：短暂丢失人体（{@link #DROPOUT_MS} 以内）不会打断正在进行的动作，
 *     避免因一帧识别失败导致漏计。</li>
 * </ol>
 */
public class PushUpCounter implements RepAnalyzer {

    // ---- 阈值（角度，单位：度） ----
    /** 手臂接近伸直的角度。 */
    private static final float TOP_ANGLE = 152f;
    /** 下放到该角度以下视为到位（有效深度）。 */
    private static final float DEPTH_ANGLE = 100f;
    /** 低于该角度才算"明显做了但不够深"，否则忽略（避免抖动误计）。 */
    private static final float PARTIAL_ANGLE = 122f;

    // ---- 阈值（比例量） ----
    /** 深度进度达到该值视为"开始下放"。 */
    private static final float DOWN_START_DEPTH = 0.22f;
    /** 深度进度回落到该值以下视为"回到撑起位"。 */
    private static final float TOP_DEPTH = 0.10f;
    /** 深度进度 ≥ 该值视为有效深度（1.0 = 完全到顶）。 */
    private static final float FULL_DEPTH = 0.85f;
    /** 深度进度 ≥ 该值算"明显做了但不够深"。 */
    private static final float PARTIAL_DEPTH = 0.45f;
    /** 髋偏离肩-踝连线超过该比例（×躯干长度）视为塌腰/撅臀。 */
    private static final float SAG_LIMIT = 0.13f;
    /** 侧对镜头时，肩-髋-踝夹角低于该值也判定为身体不直。 */
    private static final float HIP_STRAIGHT_ANGLE = 150f;
    /** 膝盖弯曲到该角度以下视为跪姿。 */
    private static final float KNEEL_ANGLE = 120f;
    /** 手腕至少要低于肩部多少（比例量）才算"撑地"姿态。 */
    private static final float MIN_WRIST_DROP = 0.02f;
    /** 躯干与水平方向的最大夹角（度），超过说明不是俯卧撑姿态。 */
    private static final float MAX_TORSO_HORIZONTAL = 55f;
    /** 一次动作的最短时长，过滤抖动。 */
    private static final long MIN_REP_MS = 350;
    /** 允许的人体丢失时长。 */
    private static final long DROPOUT_MS = 500;
    /** 深度平滑系数（仅影响显示与阶段切换）。 */
    private static final float SMOOTHING = 0.5f;

    private final ExerciseType type;
    private final String variant;
    private final RepListener listener;

    private float lastElbow = Float.NaN;
    private float smoothedDepth = Float.NaN;
    /** 撑起位时手腕比肩低的基准高度（自动校准），用于把"下降多少"换算成 0~1。 */
    private float topDrop = Float.NaN;

    private boolean downPhase;
    private long downStartMs;
    private float repMaxDepth;
    private float repMinElbow;
    private float repMaxDropDepth;
    private float repMaxSag;
    private boolean repStraight = true;
    private boolean repKneeling;

    private int goodReps;
    private int badReps;
    private int formErrors;
    private String hint = "";
    private String phaseText = "等待开始";

    private long lastValidMs = -1;
    private BodyMetrics.View lastView = BodyMetrics.View.UNKNOWN;

    public PushUpCounter(ExerciseType type, String variant, RepListener listener) {
        this.type = type == null ? ExerciseType.PUSHUP : type;
        this.variant = variant == null ? "" : variant;
        this.listener = listener;
    }

    /** 折凳"手撑凳"变体：斜面上身体行程更小，阈值放宽一些。 */
    private float depthAngle() {
        return isHandsOnChair() ? DEPTH_ANGLE + 6f : DEPTH_ANGLE;
    }

    private float angleTop() {
        return TOP_ANGLE;
    }

    /** 明显下放但未到位的角度上限（低于它才计"不标准"，否则视为抖动忽略）。 */
    private float partialAngle() {
        return isHandsOnChair() ? PARTIAL_ANGLE + 6f : PARTIAL_ANGLE;
    }

    private boolean isHandsOnChair() {
        return "hands".equals(variant);
    }

    @Override
    public void reset() {
        lastElbow = Float.NaN;
        smoothedDepth = Float.NaN;
        topDrop = Float.NaN;
        downPhase = false;
        downStartMs = 0;
        repMaxDepth = 0f;
        repMinElbow = Float.NaN;
        repMaxDropDepth = 0f;
        repMaxSag = 0f;
        repStraight = true;
        repKneeling = false;
        goodReps = 0;
        badReps = 0;
        formErrors = 0;
        hint = "";
        phaseText = "等待开始";
        lastValidMs = -1;
        lastView = BodyMetrics.View.UNKNOWN;
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
            hint = "请让肩部与髋部都进入画面";
            handleDropout(now);
            return;
        }
        lastValidMs = now;
        lastView = metrics.view;

        float elbow = PoseMath.averageAngle(frame,
                PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST);
        lastElbow = elbow;

        // ---- 姿态门槛：躯干接近水平，双手撑在肩下方 ----
        float torsoHorizontal = metrics.torsoAngleFromHorizontal();
        boolean prone = !Float.isNaN(torsoHorizontal) && torsoHorizontal < MAX_TORSO_HORIZONTAL;
        boolean handsDown = !Float.isNaN(metrics.wristDropRatio)
                && metrics.wristDropRatio > MIN_WRIST_DROP;
        if (!prone || !handsDown) {
            dropPhase();
            phaseText = "未进入俯卧撑姿态";
            hint = "侧对镜头，双手撑地/撑凳，身体放平";
            return;
        }

        // ---- 撑起位基准校准：手臂接近伸直时记下"手腕比肩低多少" ----
        boolean elbowTop = Float.isNaN(elbow) || elbow >= angleTop() - 6f;
        if (elbowTop && !Float.isNaN(metrics.wristDropRatio)) {
            topDrop = Float.isNaN(topDrop)
                    ? metrics.wristDropRatio
                    : Math.max(topDrop, metrics.wristDropRatio);
        }

        // ---- 深度 0~1（越大越深） ----
        float depthByAngle = Float.isNaN(elbow)
                ? Float.NaN
                : clamp((angleTop() - elbow) / (angleTop() - depthAngle()), 0f, 1.25f);
        float depthByDrop = dropDepth(metrics.wristDropRatio);

        boolean preferDrop = metrics.view == BodyMetrics.View.FRONTAL
                || Float.isNaN(depthByAngle);
        float depth;
        if (preferDrop && !Float.isNaN(depthByDrop)) {
            depth = depthByDrop;
        } else if (!Float.isNaN(depthByAngle)) {
            // 两个信号都有时取更灵敏的（斜对镜头时角度会被压缩）
            depth = Float.isNaN(depthByDrop) ? depthByAngle : Math.max(depthByAngle, depthByDrop);
        } else {
            depth = depthByDrop;
        }

        if (Float.isNaN(depth)) {
            phaseText = "请让手臂完整入镜";
            hint = "手臂被遮挡或超出画面";
            return;
        }
        smoothedDepth = Float.isNaN(smoothedDepth)
                ? depth
                : smoothedDepth + SMOOTHING * (depth - smoothedDepth);

        // ---- 身体是否成直线 ----
        float sag = Float.isNaN(metrics.sagRatio) ? 0f : metrics.sagRatio;
        boolean straightByRatio = Float.isNaN(metrics.sagRatio) || sag <= SAG_LIMIT;
        float hipAngle = PoseMath.averageAngle(frame,
                PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_HIP, PoseFrame.LEFT_ANKLE,
                PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_ANKLE);
        boolean straightByAngle = Float.isNaN(hipAngle)
                || metrics.view != BodyMetrics.View.SIDE
                || hipAngle >= HIP_STRAIGHT_ANGLE;
        boolean straight = straightByRatio && straightByAngle;

        float kneeAngle = PoseMath.averageAngle(frame,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE);
        boolean kneeling = !Float.isNaN(kneeAngle) && kneeAngle < KNEEL_ANGLE;

        // ---- 阶段状态机 ----
        if (!downPhase) {
            if (depth > DOWN_START_DEPTH) {
                downPhase = true;
                downStartMs = now;
                repMaxDepth = depth;
                repMinElbow = elbow;
                repMaxDropDepth = Float.isNaN(depthByDrop) ? 0f : depthByDrop;
                repMaxSag = sag;
                repStraight = straight;
                repKneeling = kneeling;
            } else {
                phaseText = depth < TOP_DEPTH ? "撑起（准备下放）" : "下放中";
                hint = "";
                return;
            }
        } else {
            repMaxDepth = Math.max(repMaxDepth, depth);
            if (!Float.isNaN(elbow)) {
                repMinElbow = Float.isNaN(repMinElbow) ? elbow : Math.min(repMinElbow, elbow);
            }
            if (!Float.isNaN(depthByDrop)) {
                repMaxDropDepth = Math.max(repMaxDropDepth, depthByDrop);
            }
            repMaxSag = Math.max(repMaxSag, sag);
            if (!straight) {
                repStraight = false;
            }
            if (kneeling) {
                repKneeling = true;
            }
        }

        // 回到撑起位 → 结算
        if (depth <= TOP_DEPTH) {
            if (downPhase) {
                finishRep(now - downStartMs);
            }
            phaseText = "撑起";
        } else {
            phaseText = downPhase ? "下放中" : "下放中";
            hint = depthHint(repMaxSag, repKneeling, depth);
        }
    }

    /** 手腕下降比例 → 0~1 深度。若基准还没建立则返回 NaN。 */
    private float dropDepth(float wristDropRatio) {
        if (Float.isNaN(wristDropRatio) || Float.isNaN(topDrop) || topDrop <= MIN_WRIST_DROP) {
            return Float.NaN;
        }
        float travel = topDrop * 0.72f; // 手腕几乎贴到肩的高度即视为到底
        return clamp((topDrop - wristDropRatio) / Math.max(0.02f, travel), 0f, 1.25f);
    }

    private void finishRep(long durationMs) {
        float minElbow = repMinElbow;
        float maxDropDepth = repMaxDropDepth;
        boolean straight = repStraight;
        boolean kneeling = repKneeling;
        float sawSag = repMaxSag;

        downPhase = false;
        repMaxDepth = 0f;
        repMinElbow = Float.NaN;
        repMaxDropDepth = 0f;
        repMaxSag = 0f;
        repStraight = true;
        repKneeling = false;

        if (durationMs < MIN_REP_MS) {
            phaseText = "撑起";
            return; // 抖动，忽略
        }

        // 深度判定：角度阈值为主（侧对镜头最准），手腕下降比例作为正对镜头/手臂遮挡时的兜底
        boolean deepEnough = (!Float.isNaN(minElbow) && minElbow <= depthAngle())
                || maxDropDepth >= FULL_DEPTH;
        boolean clearlyMoved = (!Float.isNaN(minElbow) && minElbow <= partialAngle())
                || maxDropDepth >= PARTIAL_DEPTH;

        String reason = null;
        if (kneeling) {
            reason = "膝盖弯曲（跪姿）";
        } else if (!straight) {
            reason = sawSag > SAG_LIMIT ? "塌腰/撅臀，身体未成直线" : "身体未成直线";
        } else if (!deepEnough) {
            reason = "下放幅度不足";
        }

        if (!straight || kneeling) {
            badReps++;
            formErrors++;
        } else if (deepEnough) {
            goodReps++;
        } else if (clearlyMoved) {
            badReps++;
            formErrors++;
        } else {
            phaseText = "幅度不足，未计数";
            hint = "胸口再往下放一些";
            return;
        }

        phaseText = "撑起";
        hint = reason == null ? "" : reason;
        if (listener != null) {
            listener.onRep(new RepEvent(reason == null, reason, durationMs,
                    goodReps, badReps, lastElbow, sawSag));
        }
    }

    private void handleDropout(long now) {
        if (lastValidMs < 0) {
            phaseText = "未检测到人体";
            hint = "请让全身进入画面（侧对镜头更准）";
            return;
        }
        if (now - lastValidMs > DROPOUT_MS) {
            // 丢失太久，放弃当前这次动作，避免"隔空计数"
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
        repMaxDepth = 0f;
        repMaxSag = 0f;
        repStraight = true;
        repKneeling = false;
    }

    private String depthHint(float sag, boolean kneeling, float depth) {
        if (kneeling) {
            return "膝盖弯曲了，伸直双腿";
        }
        if (sag > SAG_LIMIT) {
            return "塌腰/撅臀，请收紧核心";
        }
        if (depth > DOWN_START_DEPTH && depth < PARTIAL_DEPTH) {
            return "再往下一点";
        }
        return "";
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- ExerciseAnalyzer ----

    @Override
    public String getScoreText() {
        return (type == ExerciseType.BAD_PUSHUP ? badReps : goodReps) + " 次";
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
        return Float.isNaN(smoothedDepth) ? 0f : clamp(smoothedDepth, 0f, 1f);
    }

    public float getCurrentElbowAngle() {
        return lastElbow;
    }

    /** 最近一次识别到的机位视角（调试用）。 */
    public BodyMetrics.View getLastView() {
        return lastView;
    }
}
