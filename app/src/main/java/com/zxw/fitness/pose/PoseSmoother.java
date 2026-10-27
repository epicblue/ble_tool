package com.zxw.fitness.pose;

import com.zxw.fitness.model.PoseFrame;

/**
 * 关键点时序滤波：解决手机端姿态识别的三类抖动。
 *
 * <ol>
 *     <li><b>抖动</b>：相邻帧坐标来回跳，导致肘角/膝角在阈值附近反复穿越 → 用指数滑动平均抑制；</li>
 *     <li><b>瞬时丢失</b>：个别帧某个关键点置信度掉到阈值以下（遮挡、运动模糊）→ 用"保持最近值"补齐，
 *     最多 {@link #HOLD_MS} 毫秒；</li>
 *     <li><b>跳变</b>：偏差超过 {@link #MAX_JUMP_RATIO} × 身体尺度时暂不采用，旧值随时间失效；
 *     新位置连续稳定一段时间后才重新接纳，避免关键点永久冻结。</li>
 * </ol>
 *
 * <p>滤波在相机回调线程里对每一帧执行一次；录进骨架文件的也是滤波后的数据，
 * 使用当前算法重放，算法版本改变后旧记录的结果可能不同。</p>
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
    /** 持续稳定的新位置才重新接纳；仅凭几帧高置信度仍不能判断是否换了人。 */
    private static final long REACQUIRE_MS = 700;
    private static final long MAX_CANDIDATE_GAP_MS = 350;
    /** 没有任何可用原始观测超过此时长，才丢弃旧轨迹；单帧缺失不能绕过跳变确认。 */
    private static final long LOST_TRACK_RESET_MS = 1000;
    private static final int MIN_REACQUIRE_FRAMES = 3;
    private static final float REACQUIRE_CONF = 0.5f;

    private final float[] x = new float[PoseFrame.LANDMARK_COUNT];
    private final float[] y = new float[PoseFrame.LANDMARK_COUNT];
    private final float[] z = new float[PoseFrame.LANDMARK_COUNT];
    private final float[] conf = new float[PoseFrame.LANDMARK_COUNT];
    private final long[] lastSeenMs = new long[PoseFrame.LANDMARK_COUNT];
    private final long[] lastObservedMs = new long[PoseFrame.LANDMARK_COUNT];
    private final boolean[] hasValue = new boolean[PoseFrame.LANDMARK_COUNT];
    private final float[] candidateX = new float[PoseFrame.LANDMARK_COUNT];
    private final float[] candidateY = new float[PoseFrame.LANDMARK_COUNT];
    private final long[] candidateStartMs = new long[PoseFrame.LANDMARK_COUNT];
    private final long[] candidateLastMs = new long[PoseFrame.LANDMARK_COUNT];
    private final int[] candidateFrames = new int[PoseFrame.LANDMARK_COUNT];

    private boolean initialized;
    private long lastTimestampMs;
    private float lastKnownScale = 0.2f;
    private int rejectedJumps;
    private int reacquiredJumps;

    /** 清空历史（切换动作/重新开始训练时调用）。 */
    public void reset() {
        initialized = false;
        lastTimestampMs = 0;
        lastKnownScale = 0.2f;
        rejectedJumps = 0;
        reacquiredJumps = 0;
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            hasValue[i] = false;
            lastSeenMs[i] = 0;
            lastObservedMs[i] = 0;
            conf[i] = 0f;
            candidateFrames[i] = 0;
            candidateStartMs[i] = 0;
            candidateLastMs[i] = 0;
        }
    }

    /** 被当作跳变丢弃的关键点次数（调试用）。 */
    public int getRejectedJumps() {
        return rejectedJumps;
    }

    /** 已稳定并重新接纳的关键点次数（不是换人检测）。 */
    public int getReacquiredJumps() { return reacquiredJumps; }

    /** 当前因突跳正在等待重新定位的关键点，可供实时诊断标记。 */
    public boolean[] getPendingJumps() {
        boolean[] pending = new boolean[PoseFrame.LANDMARK_COUNT];
        for (int i = 0; i < pending.length; i++) pending[i] = candidateFrames[i] > 0;
        return pending;
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
        if (!initialized || ts < lastTimestampMs) {
            if (initialized) reset();
            initialized = true;
            copyRaw(raw);
            lastTimestampMs = ts;
            return raw;
        }

        // 不用过期或只重捕获一半的躯干扩大跳变阈值；缺少新鲜双点时沿用上次尺度。
        float scale = estimateScale(ts);
        float maxJump = Math.max(0.12f, scale * MAX_JUMP_RATIO);

        float[] outX = new float[PoseFrame.LANDMARK_COUNT];
        float[] outY = new float[PoseFrame.LANDMARK_COUNT];
        float[] outZ = new float[PoseFrame.LANDMARK_COUNT];
        float[] outC = new float[PoseFrame.LANDMARK_COUNT];

        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            float rawConf = raw.getConfidence(i);
            boolean rawUsable = rawConf >= MIN_CONF;

            if (rawUsable) {
                if (hasValue[i] && ts - lastObservedMs[i] > LOST_TRACK_RESET_MS) {
                    hasValue[i] = false;
                    candidateFrames[i] = 0;
                }
                lastObservedMs[i] = ts;
                if (hasValue[i]) {
                    float dx = raw.getX(i) - x[i];
                    float dy = raw.getY(i) - y[i];
                    float jump = (float) Math.sqrt(dx * dx + dy * dy);
                    if (jump > maxJump) {
                        float tolerance = Math.max(0.03f, scale * 0.25f);
                        boolean stable = rawConf >= REACQUIRE_CONF
                                && updateCandidate(i, raw.getX(i), raw.getY(i), ts, tolerance);
                        if (rawConf < REACQUIRE_CONF) candidateFrames[i] = 0;
                        if (stable) {
                            // 稳定新位置被接受；间隔内旧点已衰减，不把旧坐标持续当作真实姿态。
                            x[i] = outX[i] = raw.getX(i);
                            y[i] = outY[i] = raw.getY(i);
                            z[i] = outZ[i] = raw.getZ(i);
                            conf[i] = outC[i] = rawConf;
                            lastSeenMs[i] = ts;
                            candidateFrames[i] = 0;
                            reacquiredJumps++;
                        } else {
                            rejectedJumps++;
                            outX[i] = x[i];
                            outY[i] = y[i];
                            outZ[i] = z[i];
                            outC[i] = heldConfidence(i, ts) * 0.8f;
                        }
                        continue;
                    }
                    candidateFrames[i] = 0;
                    float alpha = clamp(BASE_ALPHA + (rawConf - MIN_CONF) * 0.5f, 0.25f, 0.75f);
                    outX[i] = x[i] + alpha * dx;
                    outY[i] = y[i] + alpha * dy;
                    outZ[i] = z[i] + alpha * (raw.getZ(i) - z[i]);
                    outC[i] = rawConf;
                } else {
                    candidateFrames[i] = 0;
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
                // 短暂丢失：保持最近位置，置信度按时间衰减。
                candidateFrames[i] = 0;
                outX[i] = x[i];
                outY[i] = y[i];
                outZ[i] = z[i];
                outC[i] = heldConfidence(i, ts);
            } else {
                // 输出不可用；短暂的候选缺失只能重置窗口，不能立即绕过跳变确认。
                candidateFrames[i] = 0;
                if (ts - lastObservedMs[i] > LOST_TRACK_RESET_MS) hasValue[i] = false;
                outX[i] = 0f;
                outY[i] = 0f;
                outZ[i] = 0f;
                outC[i] = 0f;
            }
        }

        lastTimestampMs = ts;
        return PoseFrame.of(ts, outX, outY, outZ, outC);
    }

    private float heldConfidence(int landmark, long timestampMs) {
        long age = Math.max(0, timestampMs - lastSeenMs[landmark]);
        if (age > HOLD_MS) return 0f;
        return conf[landmark] * (1f - 0.6f * clamp(age / (float) HOLD_MS, 0f, 1f));
    }

    /** 与第一帧候选位置比较，不让连续缓慢漂移伪装成稳定。 */
    private boolean updateCandidate(int landmark, float px, float py, long ts, float tolerance) {
        float dx = px - candidateX[landmark];
        float dy = py - candidateY[landmark];
        boolean continuous = candidateFrames[landmark] > 0 && ts >= candidateLastMs[landmark]
                && ts - candidateLastMs[landmark] <= MAX_CANDIDATE_GAP_MS
                && dx * dx + dy * dy <= tolerance * tolerance;
        if (!continuous) {
            candidateX[landmark] = px;
            candidateY[landmark] = py;
            candidateStartMs[landmark] = ts;
            candidateFrames[landmark] = 1;
        } else {
            candidateFrames[landmark]++;
        }
        candidateLastMs[landmark] = ts;
        return candidateFrames[landmark] >= MIN_REACQUIRE_FRAMES
                && ts - candidateStartMs[landmark] >= REACQUIRE_MS;
    }

    private void copyRaw(PoseFrame raw) {
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            x[i] = raw.getX(i);
            y[i] = raw.getY(i);
            z[i] = raw.getZ(i);
            conf[i] = raw.getConfidence(i);
            hasValue[i] = raw.getConfidence(i) >= MIN_CONF;
            lastSeenMs[i] = raw.getTimestampMs();
            lastObservedMs[i] = raw.getTimestampMs();
        }
    }

    private float estimateScale(long now) {
        boolean left = fresh(PoseFrame.LEFT_SHOULDER, now) && fresh(PoseFrame.LEFT_HIP, now);
        boolean right = fresh(PoseFrame.RIGHT_SHOULDER, now) && fresh(PoseFrame.RIGHT_HIP, now);
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
        if (count > 0) lastKnownScale = sum / count;
        return lastKnownScale;
    }

    private boolean fresh(int landmark, long now) {
        return hasValue[landmark] && now - lastSeenMs[landmark] <= HOLD_MS;
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
