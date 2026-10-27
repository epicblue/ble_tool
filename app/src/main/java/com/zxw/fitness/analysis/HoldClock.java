package com.zxw.fitness.analysis;

/**
 * 计时类的"有效时间"时钟。
 *
 * <p>为什么需要它：{@code 倒立 / 平板支撑 / 靠墙蹲} 都允许一段宽限期
 * （{@link HoldAnalyzer} 的 GRACE），姿势短暂不合格时不会立刻结束本组。
 * 如果直接用 {@code now - 开始时间} 作为成绩，宽限期里"站着休息"的那段也会被算进去，
 * 实测 600ms 的中断能让 3.4 秒的靠墙蹲变成 3.9 秒。</p>
 *
 * <p>这里的规则很简单：<b>只有相邻两帧都合格时，才把这两帧之间的时间计入</b>。</p>
 * <ul>
 *     <li>{@link #begin(long)}：本组开始（含 ARM 起算窗口，起算窗口内的帧都是合格的，算有效时间）；</li>
 *     <li>{@link #accept(long)}：本帧合格；</li>
 *     <li>{@link #invalidate()}：本帧不合格（包括识别失败），下一帧即使合格也不会把这段空白计入；</li>
 *     <li>{@link #end()}：本组结束。</li>
 * </ul>
 *
 * <p>判定器每帧只做三件事：合格 → {@code accept}，不合格 → {@code invalidate}，
 * 结束一组 → {@code end}。所有时长都从 {@link #getValidMs()} 取。</p>
 */
public final class HoldClock {

    private boolean active;
    private long startMs = -1;
    private long lastAcceptedMs = -1;
    private long validMs;
    private boolean previousFrameGood;

    /** 开始一组（{@code nowMs} 是起算窗口的起点）。 */
    public void begin(long nowMs) {
        active = true;
        startMs = nowMs;
        lastAcceptedMs = nowMs;
        validMs = 0;
        previousFrameGood = true;
    }

    /** 本帧合格。 */
    public void accept(long nowMs) {
        if (!active) {
            return;
        }
        if (previousFrameGood && lastAcceptedMs >= 0) {
            validMs += Math.max(0, nowMs - lastAcceptedMs);
        }
        lastAcceptedMs = nowMs;
        previousFrameGood = true;
    }

    /** 本帧不合格。 */
    public void invalidate() {
        previousFrameGood = false;
    }

    /** 本组结束，保留累计值供读取。 */
    public void end() {
        active = false;
        startMs = -1;
        lastAcceptedMs = -1;
        previousFrameGood = false;
    }

    public boolean isActive() {
        return active;
    }

    /** 本组的有效保持时间（毫秒）。 */
    public long getValidMs() {
        return validMs;
    }

    /** 本组的墙钟时长（含中断），用于计算"有效保持率"。 */
    public long getWallMs(long nowMs) {
        if (!active || startMs < 0) {
            return validMs;
        }
        return Math.max(0, nowMs - startMs);
    }

    /** 本组被中断（宽限期）的累计时长。 */
    public long getInterruptMs(long nowMs) {
        return Math.max(0, getWallMs(nowMs) - validMs);
    }
}
