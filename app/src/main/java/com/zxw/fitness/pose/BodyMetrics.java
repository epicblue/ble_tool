package com.zxw.fitness.pose;

import com.zxw.fitness.model.PoseFrame;

/**
 * 一帧的"身体度量"：把关键点换算成与镜头距离、人体远近无关的比例值。
 *
 * <p>为什么需要它：直接比较像素/归一化坐标会随手机机位、人离镜头远近而漂移。
 * 这里统一下面几个量，让判定阈值可以写成"相对量"：</p>
 * <ul>
 *     <li>{@link #bodyScale}：躯干长度（肩中点 → 髋中点），作为所有比例的基准；</li>
 *     <li>{@link #sagRatio}：髋部偏离"肩-踝"连线的距离 / bodyScale，
 *     与视角无关地衡量"身体是否一条直线"（塌腰、撅臀都会显著变大）；</li>
 *     <li>{@link #wristDropRatio}：手腕比肩部低多少 / bodyScale，
 *     用于俯卧撑/平板支撑的"下降深度"，正对镜头也有效；</li>
 *     <li>{@link #view}：根据"肩宽 / 躯干长度"比例判断是侧对还是正对镜头，
 *     决定用关节角还是用比例量作为主判据。</li>
 * </ul>
 */
public final class BodyMetrics {

    /** 机位视角。 */
    public enum View {
        /** 侧对镜头：左右肩几乎重合，关节角可靠。 */
        SIDE,
        /** 正对镜头：肩宽明显，关节角在图像上被压缩，改用比例量。 */
        FRONTAL,
        /** 信息不足。 */
        UNKNOWN
    }

    /** 关键点可用性的默认阈值。 */
    public static final float CONF = 0.5f;
    /** bodyScale 的下限，避免除以 0。 */
    private static final float MIN_SCALE = 0.04f;
    /** 肩宽 / 躯干长度 超过该值视为正对镜头。 */
    private static final float FRONTAL_RATIO = 0.62f;

    public final boolean valid;
    public final float shoulderX;
    public final float shoulderY;
    public final float hipX;
    public final float hipY;
    /** 脚踝中点不可用时为 NaN。 */
    public final float ankleX;
    public final float ankleY;
    public final boolean hasAnkle;
    /** 肩中点 → 髋中点的距离。 */
    public final float torsoLength;
    /** 左右肩距离，肩不可靠时为 NaN。 */
    public final float shoulderWidth;
    /** 归一化基准（≥ {@link #MIN_SCALE}）。 */
    public final float bodyScale;
    /** 髋部偏离肩-踝连线的距离 / bodyScale，缺脚踝时为 NaN。 */
    public final float sagRatio;
    /** 髋部在连线哪一侧：+1 表示偏向"下方"（塌腰），-1 表示偏向"上方"（撅臀），0 表示在线或不可用。 */
    public final int sagSide;
    /** (手腕中点 y - 肩中点 y) / bodyScale，正值表示双手在肩下方。 */
    public final float wristDropRatio;
    public final View view;

    private BodyMetrics(boolean valid, float shoulderX, float shoulderY, float hipX, float hipY,
                        float ankleX, float ankleY, boolean hasAnkle, float torsoLength,
                        float shoulderWidth, float bodyScale, float sagRatio, int sagSide,
                        float wristDropRatio, View view) {
        this.valid = valid;
        this.shoulderX = shoulderX;
        this.shoulderY = shoulderY;
        this.hipX = hipX;
        this.hipY = hipY;
        this.ankleX = ankleX;
        this.ankleY = ankleY;
        this.hasAnkle = hasAnkle;
        this.torsoLength = torsoLength;
        this.shoulderWidth = shoulderWidth;
        this.bodyScale = bodyScale;
        this.sagRatio = sagRatio;
        this.sagSide = sagSide;
        this.wristDropRatio = wristDropRatio;
        this.view = view;
    }

    private static BodyMetrics invalid() {
        return new BodyMetrics(false, 0, 0, 0, 0, Float.NaN, Float.NaN, false, 0, Float.NaN,
                MIN_SCALE, Float.NaN, 0, Float.NaN, View.UNKNOWN);
    }

    /** 由一帧关键点计算身体度量；肩或髋不可靠时 {@link #valid} 为 false。 */
    public static BodyMetrics of(PoseFrame f) {
        if (f == null || !f.isVisible()) {
            return invalid();
        }
        boolean leftTorso = reliable(f, PoseFrame.LEFT_SHOULDER) && reliable(f, PoseFrame.LEFT_HIP);
        boolean rightTorso = reliable(f, PoseFrame.RIGHT_SHOULDER) && reliable(f, PoseFrame.RIGHT_HIP);
        // 肩和髋必须至少有同一侧完整，不能把左肩和右髋硬拼成虚假的躯干。
        if (!leftTorso && !rightTorso) return invalid();
        boolean useLeft = leftTorso, useRight = rightTorso;
        if (leftTorso && rightTorso) {
            float leftConfidence = Math.min(f.getConfidence(PoseFrame.LEFT_SHOULDER), f.getConfidence(PoseFrame.LEFT_HIP));
            float rightConfidence = Math.min(f.getConfidence(PoseFrame.RIGHT_SHOULDER), f.getConfidence(PoseFrame.RIGHT_HIP));
            if (Math.abs(leftConfidence - rightConfidence) > PoseMath.CONFIDENCE_GAP) {
                useLeft = leftConfidence > rightConfidence;
                useRight = !useLeft;
            }
        }
        float sx = pairedX(f, PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER, useLeft, useRight);
        float sy = pairedY(f, PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER, useLeft, useRight);
        float hx = pairedX(f, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP, useLeft, useRight);
        float hy = pairedY(f, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP, useLeft, useRight);
        float torso = dist(sx, sy, hx, hy);
        float scale = Math.max(MIN_SCALE, torso);

        boolean hasShoulderWidth = reliable(f, PoseFrame.LEFT_SHOULDER)
                && reliable(f, PoseFrame.RIGHT_SHOULDER);
        float shoulderWidth = hasShoulderWidth
                ? dist(f.getX(PoseFrame.LEFT_SHOULDER), f.getY(PoseFrame.LEFT_SHOULDER),
                f.getX(PoseFrame.RIGHT_SHOULDER), f.getY(PoseFrame.RIGHT_SHOULDER))
                : Float.NaN;

        View view = View.UNKNOWN;
        if (hasShoulderWidth && torso > 0.02f) {
            view = shoulderWidth / torso >= FRONTAL_RATIO ? View.FRONTAL : View.SIDE;
        }

        boolean hasAnkle = reliable(f, PoseFrame.LEFT_ANKLE) || reliable(f, PoseFrame.RIGHT_ANKLE);
        float ax = Float.NaN;
        float ay = Float.NaN;
        float sag = Float.NaN;
        int sagSide = 0;
        if (hasAnkle) {
            ax = pairedX(f, PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE, useLeft, useRight);
            ay = pairedY(f, PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE, useLeft, useRight);
            float[] projection = projectOnLine(hx, hy, sx, sy, ax, ay);
            if (projection != null) {
                sag = dist(hx, hy, projection[0], projection[1]) / scale;
                // 叉积符号：判断髋在肩→踝连线的哪一侧（图像 y 向下）
                float cross = (ax - sx) * (hy - sy) - (ay - sy) * (hx - sx);
                sagSide = Math.abs(cross) < 1e-6f ? 0 : (cross > 0 ? 1 : -1);
            }
        }

        float wristDrop = Float.NaN;
        float wx = pairedX(f, PoseFrame.LEFT_WRIST, PoseFrame.RIGHT_WRIST, useLeft, useRight);
        float wy = pairedY(f, PoseFrame.LEFT_WRIST, PoseFrame.RIGHT_WRIST, useLeft, useRight);
        if (!Float.isNaN(wy)) {
            wristDrop = (wy - sy) / scale;
        }

        return new BodyMetrics(true, sx, sy, hx, hy, ax, ay, hasAnkle, torso, shoulderWidth,
                scale, sag, sagSide, wristDrop, view);
    }

    /** 躯干与竖直方向的夹角（度），0=竖直，90=水平；不可用为 NaN。 */
    public float torsoAngleFromVertical() {
        if (!valid) {
            return Float.NaN;
        }
        return (float) Math.toDegrees(Math.atan2(Math.abs(shoulderX - hipX),
                Math.abs(shoulderY - hipY) + 1e-5));
    }

    /** 躯干与水平方向的夹角（度），0=水平；不可用为 NaN。 */
    public float torsoAngleFromHorizontal() {
        float vertical = torsoAngleFromVertical();
        return Float.isNaN(vertical) ? Float.NaN : 90f - vertical;
    }

    /** 是否倒置：肩在髋下方（图像 y 更大）。 */
    public boolean isInverted() {
        return valid && shoulderY > hipY + 0.02f;
    }

    /** 髋部横向漂移量 / bodyScale，用于倒立稳定度；缺数据返回 NaN。 */
    public static float driftRatio(float dx, float scale) {
        return Math.abs(dx) / Math.max(MIN_SCALE, scale);
    }

    private static boolean reliable(PoseFrame f, int lm) {
        return f.isReliable(lm, CONF);
    }

    /** 只看到一侧躯干时尽量用同一侧的端点，避免另一侧残缺关键点污染比例。 */
    private static float pairedX(PoseFrame f, int left, int right, boolean leftTorso, boolean rightTorso) {
        if (leftTorso && !rightTorso && reliable(f, left)) return f.getX(left);
        if (rightTorso && !leftTorso && reliable(f, right)) return f.getX(right);
        return mid(f, left, right);
    }

    private static float pairedY(PoseFrame f, int left, int right, boolean leftTorso, boolean rightTorso) {
        if (leftTorso && !rightTorso && reliable(f, left)) return f.getY(left);
        if (rightTorso && !leftTorso && reliable(f, right)) return f.getY(right);
        return midY(f, left, right);
    }

    /** 只可靠一侧时取该侧，两侧都可靠取中点。 */
    private static float mid(PoseFrame f, int a, int b) {
        boolean ra = reliable(f, a);
        boolean rb = reliable(f, b);
        if (ra && rb) {
            return (f.getX(a) + f.getX(b)) / 2f;
        }
        return ra ? f.getX(a) : f.getX(b);
    }

    private static float midY(PoseFrame f, int a, int b) {
        boolean ra = reliable(f, a);
        boolean rb = reliable(f, b);
        if (ra && rb) {
            return (f.getY(a) + f.getY(b)) / 2f;
        }
        return ra ? f.getY(a) : f.getY(b);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** 把点 p 投影到直线 ab 上，返回投影点；ab 退化时返回 null。 */
    private static float[] projectOnLine(float px, float py, float ax, float ay,
                                         float bx, float by) {
        float abx = bx - ax;
        float aby = by - ay;
        float lenSquared = abx * abx + aby * aby;
        if (lenSquared < 1e-8f) {
            return null;
        }
        float t = ((px - ax) * abx + (py - ay) * aby) / lenSquared;
        return new float[]{ax + t * abx, ay + t * aby};
    }
}
