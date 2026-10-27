package com.zxw.fitness.analysis;

/** 以"坚持时间"为结果的判定器（倒立、平板支撑）。 */
public interface HoldAnalyzer extends ExerciseAnalyzer {

    /** 当前这一组的实时时长（毫秒）。 */
    long getCurrentHoldMs();

    /** 最好的一组（毫秒）。 */
    long getBestHoldMs();

    /** 累计保持时长（毫秒）。 */
    long getTotalHoldMs();

    /** 已完成的组数。 */
    int getAttempts();

    /** 稳定性评分 0~1（1 表示几乎不动）。 */
    float getStability();

    /** 附加信息，例如贴墙比例，可为空字符串。 */
    String getExtraText();

    /**
     * 本组被中断（宽限期）的累计时长（毫秒）。
     *
     * <p>成绩只统计"有效保持时间"，休息/调整姿势的时间不计入，但会通过这个值暴露出来，
     * 便于界面显示"有效保持率"。</p>
     */
    default long getInterruptMs() {
        return 0;
    }
}
