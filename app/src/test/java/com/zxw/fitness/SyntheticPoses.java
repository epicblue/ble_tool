package com.zxw.fitness;

import com.zxw.fitness.model.PoseFrame;

/**
 * 单元测试用的合成关键点生成器。
 *
 * <p>不依赖 Android 运行时，纯几何构造：所有坐标都是归一化图像坐标（x 向右，y 向下），
 * 与真实 ML Kit 输出格式一致，便于在没有真机的情况下验证判定逻辑。</p>
 */
final class SyntheticPoses {

    static final int FPS_STEP_MS = 66;

    private SyntheticPoses() {
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void put(float[] x, float[] y, float[] c, int index,
                            float px, float py, float conf) {
        x[index] = px;
        y[index] = py;
        c[index] = conf;
    }

    /**
     * 侧视角度俯卧撑：肩在手腕正上方，肘位于肩-腕垂直平分线上，保证夹角等于 target。
     *
     * @param sag   髋部相对肩-踝连线向下偏移的比例（0 表示身体笔直）
     * @param kneel 是否跪姿（膝盖弯曲、脚踝上移）
     */
    static PoseFrame pushUp(long ts, float targetElbowDeg, float sag, boolean kneel) {
        return pushUp(ts, targetElbowDeg, sag, kneel, 0f);
    }

    /**
     * 俯卧撑，可指定"身体下沉量"。
     *
     * <p>{@code chestDrop} &gt; 0 时手和脚位置不变、肩随身体下沉（髋只下沉一半，身体绕脚踝转动），
     * 这样"手腕比肩低多少"会随动作变化 —— 这是正对/斜对镜头时的主要判据。</p>
     */
    static PoseFrame pushUp(long ts, float targetElbowDeg, float sag, boolean kneel,
                            float chestDrop) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];

        float sx = 0.40f;
        float sy = 0.55f + chestDrop;
        float wx = 0.40f;
        float wy = 0.75f;
        float half = (wy - sy) / 2f;
        float h = (float) (half / Math.tan(Math.toRadians(targetElbowDeg) / 2.0));
        float ex = sx + h;
        float ey = (sy + wy) / 2f;

        float hipY = 0.55f + sag + chestDrop * 0.5f;
        float ankleY = 0.55f;
        float kneeX = kneel ? 0.68f : 0.70f;
        float kneeY = kneel ? 0.62f : 0.55f;
        float ankleX = kneel ? 0.78f : 0.80f;

        put(x, y, c, PoseFrame.NOSE, 0.34f, 0.52f, 0.9f);
        int[] left = {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE};
        int[] right = {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
        float[][] points = {
                {sx, sy}, {ex, ey}, {wx, wy}, {0.60f, hipY}, {kneeX, kneeY}, {ankleX, ankleY}
        };
        for (int i = 0; i < points.length; i++) {
            put(x, y, c, left[i], points[i][0], points[i][1], 0.9f);
            put(x, y, c, right[i], points[i][0], points[i][1], 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    /** 站立的人（躯干竖直、双手垂在体侧）。 */
    static PoseFrame standing(long ts) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        int[] left = {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE};
        int[] right = {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
        float[][] points = {
                {0.45f, 0.30f}, {0.45f, 0.45f}, {0.45f, 0.55f},
                {0.47f, 0.50f}, {0.47f, 0.70f}, {0.47f, 0.85f}
        };
        for (int i = 0; i < points.length; i++) {
            put(x, y, c, left[i], points[i][0], points[i][1], 0.9f);
            put(x, y, c, right[i], points[i][0], points[i][1], 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    /**
     * 深蹲：kneeDeg 控制膝角，hipDrop 控制髋部往下坐的幅度（比例）。
     */
    static PoseFrame squat(long ts, float kneeDeg, float hipDrop) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];

        float hipY = 0.42f + hipDrop * 0.25f;
        float shoulderY = hipY - 0.20f;     // 躯干长度恒定：真实下蹲时肩会跟着髋一起下沉
        float ankleY = 0.82f;
        // 膝角（髋-膝-踝）精确等于 kneeDeg：膝位于髋-踝垂直平分线上、向前偏移
        float halfHeight = (ankleY - hipY) / 2f;
        float half = (float) Math.toRadians(clamp(kneeDeg, 10f, 178f) / 2f);
        float forward = (float) (halfHeight * Math.tan(Math.PI / 2 - half));
        float kneeY = (hipY + ankleY) / 2f;

        put(x, y, c, PoseFrame.NOSE, 0.45f, shoulderY - 0.07f, 0.9f);
        for (int side = 0; side < 2; side++) {
            int[] idx = side == 0
                    ? new int[]{PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                    PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE}
                    : new int[]{PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                    PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
            put(x, y, c, idx[0], 0.45f, shoulderY, 0.9f);
            put(x, y, c, idx[1], 0.45f, shoulderY + 0.10f, 0.9f);
            put(x, y, c, idx[2], 0.45f, shoulderY + 0.18f, 0.9f);
            put(x, y, c, idx[3], 0.45f, hipY, 0.9f);
            put(x, y, c, idx[4], 0.45f + forward, kneeY, 0.9f);
            put(x, y, c, idx[5], 0.45f, ankleY, 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    /** 倒立：髋在上、肩在下、脚在最高处；tilt 控制躯干倾斜角。 */
    static PoseFrame handstand(long ts, float tiltDeg, boolean inverted) {
        float hipX = 0.5f;
        float hipY = 0.45f;
        float shoulderY = inverted ? 0.62f : 0.30f;
        float ankleY = inverted ? 0.18f : 0.75f;
        float dx = (float) (Math.tan(Math.toRadians(tiltDeg)) * (shoulderY - hipY));
        return invertedBody(ts, hipX, hipY, shoulderY, ankleY, dx, 0f);
    }

    /**
     * 平板支撑：肩、髋、踝基本水平；sag 让髋部往下掉（塌腰）。
     * 手腕在肩正下方，用于通过"撑地"判定。
     */
    static PoseFrame plank(long ts, float sag, boolean armsStraightDown) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];

        float shoulderX = 0.25f;
        float hipX = 0.55f;
        float ankleX = 0.85f;
        float shoulderY = 0.55f;
        float hipY = 0.55f + sag;      // sag > 0 表示塌腰（髋部下沉）
        float ankleY = 0.58f;

        put(x, y, c, PoseFrame.NOSE, 0.18f, 0.53f, 0.9f);
        for (int side = 0; side < 2; side++) {
            int[] idx = side == 0
                    ? new int[]{PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                    PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE}
                    : new int[]{PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                    PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
            put(x, y, c, idx[0], shoulderX, shoulderY, 0.9f);
            put(x, y, c, idx[1], shoulderX, (shoulderY + 0.78f) / 2f, 0.9f);
            put(x, y, c, idx[2], shoulderX, 0.78f, 0.9f);
            put(x, y, c, idx[3], hipX, hipY, 0.9f);
            put(x, y, c, idx[4], (hipX + ankleX) / 2f, hipY, 0.9f);
            put(x, y, c, idx[5], ankleX, ankleY, 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    private static PoseFrame invertedBody(long ts, float hipX, float hipY, float shoulderY,
                                          float ankleY, float dx, float unused) {
        return invertedBody(ts, hipX, hipY, shoulderY, ankleY, dx, unused, true);
    }

    private static PoseFrame invertedBody(long ts, float hipX, float hipY, float shoulderY,
                                          float ankleY, float dx, float unused,
                                          boolean armsStraightDown) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];

        put(x, y, c, PoseFrame.NOSE, hipX + dx, shoulderY + 0.03f, 0.9f);
        for (int side = 0; side < 2; side++) {
            int[] idx = side == 0
                    ? new int[]{PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                    PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE}
                    : new int[]{PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                    PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
            float elbowY = armsStraightDown ? shoulderY + 0.06f : shoulderY - 0.02f;
            float wristY = armsStraightDown ? 0.78f : ankleY - 0.02f;
            put(x, y, c, idx[0], hipX + dx, shoulderY, 0.9f);
            put(x, y, c, idx[1], hipX + dx + 0.03f, elbowY, 0.9f);
            put(x, y, c, idx[2], hipX + dx + 0.03f, wristY, 0.9f);
            put(x, y, c, idx[3], hipX, hipY, 0.9f);
            put(x, y, c, idx[4], hipX, (hipY + ankleY) / 2f, 0.9f);
            put(x, y, c, idx[5], hipX, ankleY, 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    /**
     * 引体向上：handY 为手腕高度（越小越高），elbowDeg 为肘角，bodyRise 为身体相对悬垂位上升的比例。
     */
    static PoseFrame pullUp(long ts, float elbowDeg, float bodyRise) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];

        float handY = 0.16f;
        float shoulderY = 0.36f - bodyRise * 0.18f;
        float hipY = 0.60f - bodyRise * 0.18f;
        float ankleY = 0.86f - bodyRise * 0.18f;
        // 肘位于肩-腕连线的垂直平分线上，使"肩-肘-腕"夹角精确等于 elbowDeg
        float midY = (shoulderY + handY) / 2f;
        float halfReach = Math.abs(shoulderY - handY) / 2f;
        float half = (float) Math.toRadians(clamp(elbowDeg, 10f, 178f) / 2f);
        float bulge = (float) (halfReach / Math.tan(half));

        put(x, y, c, PoseFrame.NOSE, 0.5f, shoulderY - 0.04f, 0.9f);
        for (int side = 0; side < 2; side++) {
            int[] idx = side == 0
                    ? new int[]{PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                    PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE}
                    : new int[]{PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                    PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
            put(x, y, c, idx[0], 0.5f, shoulderY, 0.9f);
            put(x, y, c, idx[1], 0.5f + bulge * (side == 0 ? 1 : -1), midY, 0.9f);
            put(x, y, c, idx[2], 0.5f, handY, 0.9f);
            put(x, y, c, idx[3], 0.5f, hipY, 0.9f);
            put(x, y, c, idx[4], 0.5f, (hipY + ankleY) / 2f, 0.9f);
            put(x, y, c, idx[5], 0.5f, ankleY, 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }


    /**
     * 靠墙蹲：膝盖在脚踝正上方、髋在膝盖后侧。
     *
     * <p>{@code hipLift} 是髋部相对膝盖的高度（归一化坐标）。0 = 大腿水平（标准姿势），
     * 数值越大越接近站起来。躯干长度固定，避免 bodyScale 随姿势变化导致比例判据失真。</p>
     */
    static PoseFrame wallSit(long ts, float hipLift) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];

        float kneeX = 0.62f;
        float kneeY = 0.70f;
        float hipX = kneeX - 0.18f;
        float hipY = kneeY - hipLift;
        float torso = 0.28f;
        float shoulderY = hipY - torso;

        put(x, y, c, PoseFrame.NOSE, hipX, shoulderY - 0.06f, 0.9f);
        for (int side = 0; side < 2; side++) {
            int[] idx = side == 0
                    ? new int[]{PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                    PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE}
                    : new int[]{PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                    PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
            put(x, y, c, idx[0], hipX, shoulderY, 0.9f);
            put(x, y, c, idx[1], hipX + 0.02f, shoulderY + 0.10f, 0.9f);
            put(x, y, c, idx[2], hipX + 0.02f, shoulderY + 0.17f, 0.9f);
            put(x, y, c, idx[3], hipX, hipY, 0.9f);
            put(x, y, c, idx[4], kneeX, kneeY, 0.9f);
            put(x, y, c, idx[5], kneeX, kneeY + 0.20f, 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    /**
     * 跑步/原地跑：liftLeft / liftRight 控制两只脚抬起的幅度（0 表示落地）。
     */
    static PoseFrame running(long ts, float liftLeft, float liftRight) {
        return running(ts, liftLeft, liftRight, 0f);
    }

    /** 跑步，可指定躯干上下起伏（跑步比走路明显）。 */
    static PoseFrame running(long ts, float liftLeft, float liftRight, float hipBob) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];

        float hipY = 0.50f - hipBob;
        float shoulderY = hipY - 0.26f;
        put(x, y, c, PoseFrame.NOSE, 0.5f, shoulderY - 0.06f, 0.9f);

        float[] lifts = {liftLeft, liftRight};
        for (int side = 0; side < 2; side++) {
            int[] idx = side == 0
                    ? new int[]{PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                    PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE}
                    : new int[]{PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                    PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
            float lift = lifts[side];
            put(x, y, c, idx[0], 0.44f, shoulderY, 0.9f);
            put(x, y, c, idx[1], 0.45f, shoulderY + 0.14f, 0.9f);
            put(x, y, c, idx[2], 0.45f, shoulderY + 0.24f, 0.9f);
            put(x, y, c, idx[3], 0.5f, hipY, 0.9f);
            put(x, y, c, idx[4], 0.5f, hipY + 0.16f - lift * 0.5f, 0.9f);
            put(x, y, c, idx[5], 0.5f, hipY + 0.34f - lift, 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    /** 一次完整动作的角度序列（下放 + 撑起）。 */
    /**
     * 把画面横向压缩 kx 倍（以画面中心为基准），模拟"镜头没有正对动作平面"。
     * kx = 1 表示正对；0.75 ≈ 镜头偏 41°。
     */
    static PoseFrame squeeze(PoseFrame f, float kx) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            x[i] = 0.5f + (f.getX(i) - 0.5f) * kx;
            y[i] = f.getY(i);
            z[i] = f.getZ(i);
            c[i] = f.getConfidence(i);
        }
        return PoseFrame.of(f.getTimestampMs(), x, y, z, c);
    }

    /** 把指定关键点的置信度压低（模拟单侧手臂被遮挡/误检）。 */
    static PoseFrame lowerConfidence(PoseFrame f, float confidence, int... landmarks) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            x[i] = f.getX(i);
            y[i] = f.getY(i);
            z[i] = f.getZ(i);
            c[i] = f.getConfidence(i);
        }
        for (int index : landmarks) {
            c[index] = confidence;
        }
        return PoseFrame.of(f.getTimestampMs(), x, y, z, c);
    }

    /** 平移指定关键点（模拟单侧肢体被误检）。 */
    static PoseFrame shiftLandmarks(PoseFrame f, float dx, float dy, int... landmarks) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            x[i] = f.getX(i);
            y[i] = f.getY(i);
            z[i] = f.getZ(i);
            c[i] = f.getConfidence(i);
        }
        for (int index : landmarks) {
            x[index] += dx;
            y[index] += dy;
        }
        return PoseFrame.of(f.getTimestampMs(), x, y, z, c);
    }

    static float[] repProfile(float bottomAngle, float topAngle, int framesPerHalf) {
        int nDown = framesPerHalf;
        int nUp = framesPerHalf;
        float[] angles = new float[nDown + nUp];
        for (int i = 0; i < nDown; i++) {
            angles[i] = topAngle + (bottomAngle - topAngle) * (i + 1) / nDown;
        }
        for (int i = 0; i < nUp; i++) {
            angles[nDown + i] = bottomAngle + (topAngle - bottomAngle) * (i + 1) / nUp;
        }
        return angles;
    }
}
