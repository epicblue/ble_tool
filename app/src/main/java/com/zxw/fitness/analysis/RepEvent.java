package com.zxw.fitness.analysis;

import java.util.Locale;

/**
 * 一次"计数类"动作的完成事件（俯卧撑、深蹲、引体向上共用）。
 */
public class RepEvent {

    /** 是否标准（计入有效次数）。 */
    public final boolean valid;
    /** 不标准的原因，标准时为 null。 */
    public final String reason;
    /** 本次动作耗时（毫秒）。 */
    public final long durationMs;
    /** 完成后的有效次数。 */
    public final int goodReps;
    /** 完成后的不标准次数。 */
    public final int badReps;
    /** 关键角度等辅助信息（按下标含义由各判定器定义，可为 NaN）。 */
    public final float minAngle;
    public final float sagRatio;

    public RepEvent(boolean valid, String reason, long durationMs,
                    int goodReps, int badReps, float minAngle, float sagRatio) {
        this.valid = valid;
        this.reason = reason;
        this.durationMs = durationMs;
        this.goodReps = goodReps;
        this.badReps = badReps;
        this.minAngle = minAngle;
        this.sagRatio = sagRatio;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "RepEvent{valid=%s, reason=%s, min=%.1f, sag=%.2f}",
                valid, reason, minAngle, sagRatio);
    }
}
