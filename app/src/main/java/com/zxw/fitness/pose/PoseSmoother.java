package com.zxw.fitness.pose;

import com.zxw.fitness.model.PoseFrame;

/**
 * 关键点时序滤波：解决手机端姿态识别的三类抖动。
 *
 * <ol>
 *     <li><b>抖动</b>：相邻帧坐标来回跳，导致肘角/膝角在阈值附近反复穿越 → 用指数滑动平均抑制；</li>
 *     <li><b>瞬时丢失</b>：个别帧某个关键点置信度掉到阈值以下（遮挡、运动模糊）→ 用"保持最近值"补齐，
 *     最多 {@link #HOLD_MS} 毫秒；</li>
 *     <li><b>跳变</b>：识别结果突然跳到别处（换人、误检）→ 与上一帧偏差超过
 *     {@link #MAX_JUMP_RATIO} × 身体尺度时视为异常值，直接忽略。</li>
 * </ol>
 *
 * <p>滤波在相机回调线程里对每一帧执行一次；录进骨架文件的也是滤波后的数据，
 * 因此回放时重放的判定结果与录制时一致。</p>
 */
public class PoseSmoother {

    /** 认为关键点"有效"的最低置信度。 */
    private static final float MIN_CONF = 0.3f;
    /** 滑动平均系数：越大越跟手、越小越平滑。 */
    private static final float BASE_ALPHA = 0.45f;
    /** 单帧跳变上限（相对身体尺度）。 */
    private static final float MAX_JUMP_RATIO = 0.6f;
    /** 关键点丢失后保持最近值的时长。 */
    private static final long HOLD_MS = 350;

    private final float[] x = new float[PoseFrame.LANDMARK_COUNT];
    private final float[] y = new float[PoseFrame.LANDMARK_COUNT];
    private final float[] z = new float[PoseFrame.LANDMARK_COUNT];
    private final float[] conf = new float[PoseFrame.LANDMARK_COUNT];
    private final long[] lastSeenMs = new long[PoseFrame.LANDMARK_COUNT];
    private final boolean[] hasValue = new boolean[PoseFrame.LANDMARK_COUNT];

    private boolean initialized;
    private long lastTimestampMs;
    private int rejectedJumps;

    /** 清空历史（切换动作/重新开始训练时调用）。 */
    public void reset() {
        initialized = false;
        lastTimestampMs = 0;
        rejectedJumps = 0;
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            hasValue[i] = false;
            lastSeenMs[i] = 0;
            conf[i] = 0f;
        }
    }

    /** 被当作跳变丢弃的关键点次数（调试用）。 */
    public int getRejectedJumps() {
        return rejectedJumps;
    }

    /**
     * 滤波一帧。
     *
     * @return 滤波后的新帧（不会修改入参）
     */
    public PoseFrame smooth(PoseFrame raw) {
        if (raw == null) {
            return PoseFrame.empty(0);
        }
        long ts = raw.getTimestampMs();
        if (!initialized) {
            initialized = true;
            copyRaw(raw);
            lastTimestampMs = ts;
            return raw;
        }

        // 身体尺度：优先用上一帧的肩-髋距离，估算跳变阈值
        float scale = estimateScale();
        float maxJump = Math.max(0.12f, scale * MAX_JUMP_RATIO);

        float[] outX = new float[PoseFrame.LANDMARK_COUNT];
        float[] outY = new float[PoseFrame.LANDMARK_COUNT];
        float[] outZ = new float[PoseFrame.LANDMARK_COUNT];
        float[] outC = new float[PoseFrame.LANDMARK_COUNT];

        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            float rawConf = raw.getConfidence(i);
            boolean rawUsable = rawConf >= MIN_CONF;

            if (rawUsable) {
                if (hasValue[i]) {
                    float dx = raw.getX(i) - x[i];
                    float dy = raw.getY(i) - y[i];
                    float jump = (float) Math.sqrt(dx * dx + dy * dy);
                    if (jump > maxJump) {
                        // 突跳：保留上一帧的值，慢慢过渡（避免误检导致计数错乱）
                        rejectedJumps++;
                        outX[i] = x[i];
                        outY[i] = y[i];
                        outZ[i] = z[i];
                        outC[i] = conf[i] * 0.8f;
                        continue;
                    }
                    float alpha = clamp(BASE_ALPHA + (rawConf - MIN_CONF) * 0.5f, 0.25f, 0.75f);
                    outX[i] = x[i] + alpha * dx;
                    outY[i] = y[i] + alpha * dy;
                    outZ[i] = z[i] + alpha * (raw.getZ(i) - z[i]);
                    outC[i] = rawConf;
                } else {
                    outX[i] = raw.getX(i);
                    outY[i] = raw.getY(i);
                    outZ[i] = raw.getZ(i);
                    outC[i] = rawConf;
                }
                x[i] = outX[i];
                y[i] = outY[i];
                z[i] = outZ[i];
                conf[i] = outC[i];
                hasValue[i] = true;
                lastSeenMs[i] = ts;
            } else if (hasValue[i] && ts - lastSeenMs[i] <= HOLD_MS) {
                // 短暂丢失：保持最近位置，置信度按时间衰减
                float age = (ts - lastSeenMs[i]) / (float) HOLD_MS;
                outX[i] = x[i];
                outY[i] = y[i];
                outZ[i] = z[i];
                outC[i] = conf[i] * (1f - 0.6f * clamp(age, 0f, 1f));
            } else {
                // 长时间丢失：标记为不可用
                hasValue[i] = false;
                outX[i] = 0f;
                outY[i] = 0f;
                outZ[i] = 0f;
                outC[i] = 0f;
            }
        }

        lastTimestampMs = ts;
        return PoseFrame.of(ts, outX, outY, outZ, outC);
    }

    private void copyRaw(PoseFrame raw) {
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            x[i] = raw.getX(i);
            y[i] = raw.getY(i);
            z[i] = raw.getZ(i);
            conf[i] = raw.getConfidence(i);
            hasValue[i] = raw.getConfidence(i) >= MIN_CONF;
            lastSeenMs[i] = raw.getTimestampMs();
        }
    }

    private float estimateScale() {
        boolean left = hasValue[PoseFrame.LEFT_SHOULDER] && hasValue[PoseFrame.LEFT_HIP];
        boolean right = hasValue[PoseFrame.RIGHT_SHOULDER] && hasValue[PoseFrame.RIGHT_HIP];
        float sum = 0f;
        int count = 0;
        if (left) {
            sum += distance(PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_HIP);
            count++;
        }
        if (right) {
            sum += distance(PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP);
            count++;
        }
        if (count == 0) {
            return 0.2f;
        }
        return sum / count;
    }

    private float distance(int a, int b) {
        float dx = x[a] - x[b];
        float dy = y[a] - y[b];
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
