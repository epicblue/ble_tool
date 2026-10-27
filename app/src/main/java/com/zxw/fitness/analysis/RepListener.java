package com.zxw.fitness.analysis;

/** 计数类动作的事件回调。 */
public interface RepListener {
    /** 完成一次动作（无论标准与否）。 */
    void onRep(RepEvent event);
}
