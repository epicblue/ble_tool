package com.zxw.fitness.pose;

import com.zxw.fitness.model.PoseFrame;

/**
 * 基于归一化关键点坐标的几何工具。
 *
 * <p>坐标约定：x 向右增大，y 向下增大（图片坐标），因此"人倒立"表现为肩膀的 y 大于髋部。</p>
 */
public final class PoseMath {

    private PoseMath() {
    }

    /** 关键点默认可信阈值。 */
    public static final float MIN_CONFIDENCE = 0.5f;

    public static float midX(PoseFrame f, int a, int b) {
        return (f.getX(a) + f.getX(b)) / 2f;
    }

    public static float midY(PoseFrame f, int a, int b) {
        return (f.getY(a) + f.getY(b)) / 2f;
    }

    /** 仅对可信关键点求中点；单侧被遮挡时使用可见的一侧。 */
    public static float reliableMidY(PoseFrame f, int left, int right) {
        boolean l = isReliable(f, left);
        boolean r = isReliable(f, right);
        if (l && r) {
            float lc = f.getConfidence(left), rc = f.getConfidence(right);
            if (Math.abs(lc - rc) > CONFIDENCE_GAP) return lc > rc ? f.getY(left) : f.getY(right);
            return (f.getY(left) * lc + f.getY(right) * rc) / (lc + rc);
        }
        if (l) return f.getY(left);
        return r ? f.getY(right) : Float.NaN;
    }

    public static float distance(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * 以 b 为顶点的夹角（度）。任一关键点不可信时返回 NaN。
     */
    public static float angle(PoseFrame f, int a, int b, int c) {
        if (!isReliable(f, a) || !isReliable(f, b) || !isReliable(f, c)) {
            return Float.NaN;
        }
        return angle(f.getX(a), f.getY(a), f.getX(b), f.getY(b), f.getX(c), f.getY(c));
    }

    public static float angle(float ax, float ay, float bx, float by, float cx, float cy) {
        double v1x = ax - bx;
        double v1y = ay - by;
        double v2x = cx - bx;
        double v2y = cy - by;
        double n1 = Math.sqrt(v1x * v1x + v1y * v1y);
        double n2 = Math.sqrt(v2x * v2x + v2y * v2y);
        if (n1 < 1e-5 || n2 < 1e-5) {
            return Float.NaN;
        }
        double cos = (v1x * v2x + v1y * v2y) / (n1 * n2);
        cos = Math.max(-1.0, Math.min(1.0, cos));
        return (float) Math.toDegrees(Math.acos(cos));
    }

    /** 两侧置信度差超过它时，只信更可信的一侧。 */
    public static final float CONFIDENCE_GAP = 0.25f;
    /** 两侧角度差超过它时，视为其中一侧的关键点被污染。 */
    public static final float DISAGREE_ANGLE = 35f;

    /**
     * 左右两侧关节角融合。
     *
     * <p>直接取算术平均的问题：只要一侧关键点被遮挡/误检（哪怕置信度还有 0.6），
     * 平均值就会被拖偏。实测一侧偏移 48° 时，平均值偏了 24°，足够把"撑到位"判成"没撑起"。</p>
     *
     * <p>融合规则：</p>
     * <ol>
     *     <li>只有一侧可靠 → 用该侧；</li>
     *     <li>两侧置信度差 &gt; {@link #CONFIDENCE_GAP} → 只信高的一侧；</li>
     *     <li>两侧角度差 &gt; {@link #DISAGREE_ANGLE} → 只信置信度高的一侧；</li>
     *     <li>其余情况按置信度加权平均。</li>
     * </ol>
     */
    public static float averageAngle(PoseFrame f, int aL, int bL, int cL, int aR, int bR, int cR) {
        float left = angle(f, aL, bL, cL);
        float right = angle(f, aR, bR, cR);
        boolean hasLeft = !Float.isNaN(left);
        boolean hasRight = !Float.isNaN(right);
        if (!hasLeft || !hasRight) {
            return hasLeft ? left : right;
        }
        float cl = minConfidence(f, aL, bL, cL);
        float cr = minConfidence(f, aR, bR, cR);
        if (Math.abs(cl - cr) > CONFIDENCE_GAP) {
            return cl > cr ? left : right;
        }
        if (Math.abs(left - right) > DISAGREE_ANGLE) {
            return cl >= cr ? left : right;
        }
        if (cl + cr <= 0f) {
            return (left + right) / 2f;
        }
        return (left * cl + right * cr) / (cl + cr);
    }

    private static float minConfidence(PoseFrame f, int a, int b, int c) {
        return Math.min(f.getConfidence(a), Math.min(f.getConfidence(b), f.getConfidence(c)));
    }

    /** 躯干与竖直方向的夹角（度）：0 表示完全竖直，90 表示完全水平。 */
    public static float torsoAngleFromVertical(PoseFrame f) {
        if (!isReliable(f, PoseFrame.LEFT_SHOULDER) || !isReliable(f, PoseFrame.RIGHT_SHOULDER)
                || !isReliable(f, PoseFrame.LEFT_HIP) || !isReliable(f, PoseFrame.RIGHT_HIP)) {
            return Float.NaN;
        }
        float dx = midX(f, PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER)
                - midX(f, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP);
        float dy = midY(f, PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER)
                - midY(f, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP);
        return (float) Math.toDegrees(Math.atan2(Math.abs(dx), Math.abs(dy) + 1e-5));
    }

    /** 躯干与水平方向的夹角（度）：0 表示完全水平（俯卧撑姿态），90 表示竖直站立。 */
    public static float torsoAngleFromHorizontal(PoseFrame f) {
        float fromVertical = torsoAngleFromVertical(f);
        if (Float.isNaN(fromVertical)) {
            return Float.NaN;
        }
        return 90f - fromVertical;
    }

    /**
     * 身体是否倒置：髋高于肩（图片中 y 更小），且双脚高于髋。
     */
    public static boolean isInverted(PoseFrame f) {
        if (!isReliable(f, PoseFrame.LEFT_SHOULDER) || !isReliable(f, PoseFrame.RIGHT_SHOULDER)
                || !isReliable(f, PoseFrame.LEFT_HIP) || !isReliable(f, PoseFrame.RIGHT_HIP)) {
            return false;
        }
        float shoulderY = midY(f, PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER);
        float hipY = midY(f, PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP);
        if (shoulderY <= hipY + 0.02f) {
            return false; // 肩不低于髋，说明不是倒立
        }
        float ankleY = ankleMidY(f);
        if (Float.isNaN(ankleY)) {
            return true; // 看不到脚时只依赖躯干方向
        }
        return ankleY < shoulderY + 0.05f;
    }

    public static float ankleMidY(PoseFrame f) {
        if (!isReliable(f, PoseFrame.LEFT_ANKLE) && !isReliable(f, PoseFrame.RIGHT_ANKLE)) {
            return Float.NaN;
        }
        if (isReliable(f, PoseFrame.LEFT_ANKLE) && isReliable(f, PoseFrame.RIGHT_ANKLE)) {
            return midY(f, PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE);
        }
        return isReliable(f, PoseFrame.LEFT_ANKLE) ? f.getY(PoseFrame.LEFT_ANKLE) : f.getY(PoseFrame.RIGHT_ANKLE);
    }

    public static float ankleMidX(PoseFrame f) {
        if (!isReliable(f, PoseFrame.LEFT_ANKLE) && !isReliable(f, PoseFrame.RIGHT_ANKLE)) {
            return Float.NaN;
        }
        if (isReliable(f, PoseFrame.LEFT_ANKLE) && isReliable(f, PoseFrame.RIGHT_ANKLE)) {
            return midX(f, PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE);
        }
        return isReliable(f, PoseFrame.LEFT_ANKLE) ? f.getX(PoseFrame.LEFT_ANKLE) : f.getX(PoseFrame.RIGHT_ANKLE);
    }

    public static boolean isReliable(PoseFrame f, int landmark) {
        return f != null && f.isReliable(landmark, MIN_CONFIDENCE);
    }

    /** 躯干是否接近水平：俯卧撑应在 0~40 度范围内。 */
    public static boolean isHorizontalTorso(PoseFrame f) {
        float horizontal = torsoAngleFromHorizontal(f);
        return !Float.isNaN(horizontal) && horizontal < 45f;
    }

    /** 某个关键点的置信度文本，便于调试。 */
    public static String describe(PoseFrame f) {
        if (f == null) {
            return "无数据";
        }
        if (!f.isVisible()) {
            return "未检测到人体";
        }
        return String.format(java.util.Locale.US, "肩%.2f 髋%.2f 踝%.2f",
                f.getConfidence(PoseFrame.LEFT_SHOULDER),
                f.getConfidence(PoseFrame.LEFT_HIP),
                f.getConfidence(PoseFrame.LEFT_ANKLE));
    }

    /** 计算最近 n 个采样的极差，用于判断稳定性。 */
    public static float spread(float[] values, int count) {
        if (count <= 1) {
            return 0f;
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        int start = Math.max(0, count - values.length);
        for (int i = start; i < count; i++) {
            min = Math.min(min, values[i]);
            max = Math.max(max, values[i]);
        }
        return max - min;
    }
}
