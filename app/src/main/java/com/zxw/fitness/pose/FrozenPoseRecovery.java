package com.zxw.fitness.pose;

import com.zxw.fitness.model.PoseFrame;

/**
 * 极保守的原始模型输出冻结检测，不修改普通跟踪或滤波。
 * 只有 1–2 个点明显移动、其余可靠点几乎逐像素不变并持续数秒才尝试重建跟踪器。
 * 这只是异常启发式，不能证明人体真的冻结或识别到了同一个人。
 */
public final class FrozenPoseRecovery {
    // 与实时骨架显示的全部 17 个点一致；不能忽略正在运动的鼻/脚点。
    private static final int[] TRACKED = PoseFrame.LANDMARK_SUBSET;
    private static final float CONFIDENCE = 0.5f;
    // 坐标按画面宽归一化：0.0004 约为 480px 画面中的 0.2px。
    private static final float STILL_DISTANCE = 0.0004f;
    private static final float MOVING_DISTANCE = 0.015f;
    private static final float RECOVERED_DISTANCE = 0.0025f;
    // 任何显示点缺失/低可信都无法确认“其他点完全不动”，宁可漏检。
    private static final int MIN_RELIABLE = PoseFrame.LANDMARK_SUBSET.length;
    private static final int MIN_STILL = MIN_RELIABLE - 2;
    private static final int MIN_FRAMES = 8;
    private static final long WINDOW_MS = 2000;
    private static final long MAX_GAP_MS = 500;
    private static final long COOLDOWN_MS = 8000;

    private final float[] startX = new float[TRACKED.length];
    private final float[] startY = new float[TRACKED.length];
    private final float[] maxDistance = new float[TRACKED.length];
    private long startMs = -1;
    private long lastMs = -1;
    private long lastAttemptMs = -1;
    private int mask;
    private int frames;
    private int width;
    private int height;
    private int frozenMask;

    /** 相机停止、切镜头或时间基准改变时丢弃所有证据。 */
    public void reset() {
        clearWindow();
        lastAttemptMs = -1;
        frozenMask = 0;
    }

    private void clearWindow() {
        startMs = -1;
        lastMs = -1;
        mask = 0;
        frames = 0;
    }

    /** 必须传滤波、重力旋转前的相机/视频原始输出。 */
    public boolean observe(PoseFrame frame, int sourceWidth, int sourceHeight) {
        if (frame == null || !frame.isVisible() || sourceWidth <= 0 || sourceHeight <= 0) {
            clearWindow();
            return false;
        }
        long now = frame.getTimestampMs();
        if (now < 0 || (lastMs >= 0 && now <= lastMs)) {
            // 重复/回退的时间不能充当持续证据，也不沿用旧冷却时钟。
            clearWindow();
            lastAttemptMs = -1;
            frozenMask = 0;
            return false;
        }
        if (lastAttemptMs >= 0 && now < lastAttemptMs) {
            clearWindow();
            lastAttemptMs = -1;
            frozenMask = 0;
        }
        if (lastAttemptMs >= 0 && now - lastAttemptMs < COOLDOWN_MS) {
            clearWindow();
            return false;
        }
        int currentMask = reliableMask(frame, sourceWidth, sourceHeight);
        if (Integer.bitCount(currentMask) < MIN_RELIABLE || !hasFullSide(frame, sourceWidth, sourceHeight)) {
            clearWindow();
            return false;
        }
        if (startMs < 0 || currentMask != mask || sourceWidth != width || sourceHeight != height
                || now - lastMs > MAX_GAP_MS) {
            begin(frame, sourceWidth, sourceHeight, currentMask);
            return false;
        }
        lastMs = now;
        frames++;
        for (int i = 0; i < TRACKED.length; i++) {
            if ((mask & (1 << i)) == 0) continue;
            float dx = frame.getX(TRACKED[i]) - startX[i];
            float dy = frame.getY(TRACKED[i]) - startY[i];
            maxDistance[i] = Math.max(maxDistance[i], (float) Math.hypot(dx, dy));
        }
        if (now - startMs < WINDOW_MS || frames < MIN_FRAMES) return false;

        int still = 0;
        int moving = 0;
        int uncertain = 0;
        int stillMask = 0;
        for (int i = 0; i < TRACKED.length; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (maxDistance[i] <= STILL_DISTANCE) {
                still++;
                stillMask |= 1 << i;
            } else if (maxDistance[i] >= MOVING_DISTANCE) {
                moving++;
            } else {
                uncertain++;
            }
        }
        if (moving >= 1 && moving <= 2 && still >= MIN_STILL && uncertain == 0) {
            frozenMask = stillMask;
            lastAttemptMs = now;
            clearWindow();
            return true;
        }
        // 没有满足全部条件，重新观察，不把上一窗口的中间移动累计为新异常。
        begin(frame, sourceWidth, sourceHeight, currentMask);
        return false;
    }

    private void begin(PoseFrame frame, int sourceWidth, int sourceHeight, int currentMask) {
        startMs = lastMs = frame.getTimestampMs();
        mask = currentMask;
        width = sourceWidth;
        height = sourceHeight;
        frames = 1;
        for (int i = 0; i < TRACKED.length; i++) {
            startX[i] = frame.getX(TRACKED[i]);
            startY[i] = frame.getY(TRACKED[i]);
            maxDistance[i] = 0f;
        }
    }

    /**
     * 重建后的第一帧必须仍有可靠的完整一侧身体，且至少三个先前静止点有变化；
     * 只找到了腿、仍然原地冻结或候选推理失败时，保留旧跟踪器。
     */
    public boolean accepts(PoseFrame original, PoseFrame candidate, int sourceWidth, int sourceHeight) {
        if (frozenMask == 0 || original == null || candidate == null
                || candidate.getTimestampMs() != original.getTimestampMs()
                || !hasFullSide(candidate, sourceWidth, sourceHeight)
                || Integer.bitCount(reliableMask(candidate, sourceWidth, sourceHeight)) < 8) return false;
        int changed = 0;
        for (int i = 0; i < TRACKED.length; i++) {
            if ((frozenMask & (1 << i)) == 0 || !reliable(candidate, TRACKED[i], sourceWidth, sourceHeight)) {
                continue;
            }
            float dx = candidate.getX(TRACKED[i]) - original.getX(TRACKED[i]);
            float dy = candidate.getY(TRACKED[i]) - original.getY(TRACKED[i]);
            if (Math.hypot(dx, dy) >= RECOVERED_DISTANCE) changed++;
        }
        return changed >= 3;
    }

    private static int reliableMask(PoseFrame frame, int width, int height) {
        int result = 0;
        for (int i = 0; i < TRACKED.length; i++) {
            if (reliable(frame, TRACKED[i], width, height)) result |= 1 << i;
        }
        return result;
    }

    private static boolean hasFullSide(PoseFrame frame, int width, int height) {
        return side(frame, width, height, PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_HIP,
                PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE)
                || side(frame, width, height, PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP,
                PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE);
    }

    private static boolean side(PoseFrame frame, int width, int height, int shoulder, int hip,
                                int knee, int ankle) {
        return reliable(frame, shoulder, width, height) && reliable(frame, hip, width, height)
                && reliable(frame, knee, width, height) && reliable(frame, ankle, width, height);
    }

    private static boolean reliable(PoseFrame frame, int landmark, int width, int height) {
        if (frame == null) return false;
        float confidence = frame.getConfidence(landmark);
        return confidence >= CONFIDENCE && confidence <= 1f
                && frame.isWithinImage(landmark, width, height, false);
    }
}
