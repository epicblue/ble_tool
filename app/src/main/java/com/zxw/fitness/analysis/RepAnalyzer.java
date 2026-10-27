package com.zxw.fitness.analysis;

/** 以"次数"为结果的判定器（俯卧撑、深蹲、引体向上…）。 */
public interface RepAnalyzer extends ExerciseAnalyzer {

    /** 有效次数。 */
    int getGoodReps();

    /** 不标准次数。 */
    int getBadReps();

    /** 姿势问题累计次数（含"不标准"与过程中的提醒）。 */
    int getFormErrors();

    /** 当前动作幅度进度 0~1（用于界面进度条），无该概念时返回 0。 */
    float getDepthProgress();
}
