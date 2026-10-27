package com.zxw.fitness.analysis;

/** 计时类动作（倒立、平板支撑）的事件回调。 */
public interface HoldListener {
    /** 开始一组保持。 */
    void onHoldStart(int attempt);

    /** 结束一组保持，参数为本组时长（毫秒）。 */
    void onHoldEnd(int attempt, long durationMs);
}
