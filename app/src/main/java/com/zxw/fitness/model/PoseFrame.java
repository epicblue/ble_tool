package com.zxw.fitness.model;

import java.util.Arrays;

/**
 * 一帧人体关键点数据（与 Google ML Kit Pose 的 33 个关键点一一对应）。
 *
 * <p>坐标使用归一化图像坐标：x ∈ [0,1] 从左到右，y ∈ [0,1] 从上到下，z 约为相对深度。
 * 归一化坐标与画面方向无关，因此录像与骨架回放的尺寸可以不同。</p>
 */
public class PoseFrame {

    /** ML Kit Pose 关键点总数。 */
    public static final int LANDMARK_COUNT = 33;

    // ---- 关键点下标（与 ML Kit PoseLandmark 常量保持一致） ----
    public static final int NOSE = 0;
    public static final int LEFT_EYE_INNER = 1;
    public static final int LEFT_EYE = 2;
    public static final int LEFT_EYE_OUTER = 3;
    public static final int RIGHT_EYE_INNER = 4;
    public static final int RIGHT_EYE = 5;
    public static final int RIGHT_EYE_OUTER = 6;
    public static final int LEFT_EAR = 7;
    public static final int RIGHT_EAR = 8;
    public static final int LEFT_MOUTH = 9;
    public static final int RIGHT_MOUTH = 10;
    public static final int LEFT_SHOULDER = 11;
    public static final int RIGHT_SHOULDER = 12;
    public static final int LEFT_ELBOW = 13;
    public static final int RIGHT_ELBOW = 14;
    public static final int LEFT_WRIST = 15;
    public static final int RIGHT_WRIST = 16;
    public static final int LEFT_PINKY = 17;
    public static final int RIGHT_PINKY = 18;
    public static final int LEFT_INDEX = 19;
    public static final int RIGHT_INDEX = 20;
    public static final int LEFT_THUMB = 21;
    public static final int RIGHT_THUMB = 22;
    public static final int LEFT_HIP = 23;
    public static final int RIGHT_HIP = 24;
    public static final int LEFT_KNEE = 25;
    public static final int RIGHT_KNEE = 26;
    public static final int LEFT_ANKLE = 27;
    public static final int RIGHT_ANKLE = 28;
    public static final int LEFT_HEEL = 29;
    public static final int RIGHT_HEEL = 30;
    public static final int LEFT_FOOT_INDEX = 31;
    public static final int RIGHT_FOOT_INDEX = 32;

    /** 用于录制的关键点子集：覆盖头、四肢、躯干，足够画出骨架。 */
    public static final int[] LANDMARK_SUBSET = {
            NOSE,
            LEFT_SHOULDER, RIGHT_SHOULDER,
            LEFT_ELBOW, RIGHT_ELBOW,
            LEFT_WRIST, RIGHT_WRIST,
            LEFT_HIP, RIGHT_HIP,
            LEFT_KNEE, RIGHT_KNEE,
            LEFT_ANKLE, RIGHT_ANKLE,
            LEFT_HEEL, RIGHT_HEEL,
            LEFT_FOOT_INDEX, RIGHT_FOOT_INDEX
    };

    private static final int SUBSET_INDEX[] = buildSubsetIndex();

    private static int[] buildSubsetIndex() {
        int[] index = new int[LANDMARK_COUNT];
        Arrays.fill(index, -1);
        for (int i = 0; i < LANDMARK_SUBSET.length; i++) {
            index[LANDMARK_SUBSET[i]] = i;
        }
        return index;
    }

    private final long timestampMs;
    private final boolean visible;
    private final float[] x;
    private final float[] y;
    private final float[] z;
    private final float[] confidence;

    private PoseFrame(long timestampMs, boolean visible, float[] x, float[] y,
                      float[] z, float[] confidence) {
        this.timestampMs = timestampMs;
        this.visible = visible;
        this.x = x;
        this.y = y;
        this.z = z;
        this.confidence = confidence;
    }

    /** 创建一个"未检测到人体"的帧。 */
    public static PoseFrame empty(long timestampMs) {
        float[] zeros = new float[LANDMARK_COUNT];
        float[] conf = new float[LANDMARK_COUNT];
        return new PoseFrame(timestampMs, false, zeros, zeros, zeros, conf);
    }

    /** 由完整关键点数组创建一帧。 */
    public static PoseFrame of(long timestampMs, float[] x, float[] y, float[] z, float[] confidence) {
        if (x.length < LANDMARK_COUNT) {
            throw new IllegalArgumentException("需要 " + LANDMARK_COUNT + " 个关键点");
        }
        boolean any = false;
        for (float c : confidence) {
            if (c > 0.3f) {
                any = true;
                break;
            }
        }
        return new PoseFrame(timestampMs, any, x, y, z, confidence);
    }

    public long getTimestampMs() {
        return timestampMs;
    }

    /** 该帧是否检测到人体。 */
    public boolean isVisible() {
        return visible;
    }

    public float getX(int landmark) {
        return x[landmark];
    }

    public float getY(int landmark) {
        return y[landmark];
    }

    public float getZ(int landmark) {
        return z[landmark];
    }

    /** 关键点置信度 0~1。 */
    public float getConfidence(int landmark) {
        return confidence[landmark];
    }

    /** 关键点是否可用（置信度足够）。 */
    public boolean isReliable(int landmark, float threshold) {
        return visible && confidence[landmark] >= threshold;
    }

    public boolean isReliable(int landmark) {
        return isReliable(landmark, 0.5f);
    }

    /** 中点关键点（例如两肩中点），任一侧不可靠时返回 null。 */
    public float[] midpoint(int a, int b) {
        if (!isReliable(a) || !isReliable(b)) {
            return null;
        }
        return new float[]{ (x[a] + x[b]) / 2f, (y[a] + y[b]) / 2f };
    }

    /** 取出录制用的子集坐标，返回 float[17*4]，顺序为 x,y,z,confidence。 */
    public float[] toCompactArray() {
        float[] out = new float[LANDMARK_SUBSET.length * 4];
        for (int i = 0; i < LANDMARK_SUBSET.length; i++) {
            int lm = LANDMARK_SUBSET[i];
            out[i * 4] = x[lm];
            out[i * 4 + 1] = y[lm];
            out[i * 4 + 2] = z[lm];
            out[i * 4 + 3] = confidence[lm];
        }
        return out;
    }

    /** 由录制数据还原一帧（未记录的关键点置信度为 0）。 */
    public static PoseFrame fromCompactArray(long timestampMs, float[] compact) {
        float[] x = new float[LANDMARK_COUNT];
        float[] y = new float[LANDMARK_COUNT];
        float[] z = new float[LANDMARK_COUNT];
        float[] conf = new float[LANDMARK_COUNT];
        int count = Math.min(LANDMARK_SUBSET.length, compact.length / 4);
        boolean any = false;
        for (int i = 0; i < count; i++) {
            int lm = LANDMARK_SUBSET[i];
            x[lm] = compact[i * 4];
            y[lm] = compact[i * 4 + 1];
            z[lm] = compact[i * 4 + 2];
            conf[lm] = compact[i * 4 + 3];
            if (conf[lm] > 0.3f) {
                any = true;
            }
        }
        return new PoseFrame(timestampMs, any, x, y, z, conf);
    }

    public static int subsetIndexOf(int landmark) {
        return index[landmark];
    }
}
