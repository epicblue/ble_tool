package com.zxw.fitness.analysis;

import com.zxw.fitness.model.PoseFrame;

/**
 * 动作判定器统一接口：实时训练与回放共用同一套判定逻辑，
 * 因此回放时看到的计数、计时与录制时完全一致。
 */
public interface ExerciseAnalyzer {

    /** 清空全部状态。 */
    void reset();

    /**
     * 处理一帧关键点。实时模式由相机回调驱动，回放模式由播放器按录制时间戳驱动；
     * 判定只依赖帧内的 timestampMs，所以两者结果相同。
     */
    void process(PoseFrame frame);

    /** 主结果文案，例如 "12 次"、"48.6 秒"。 */
    String getScoreText();

    /** 当前状态/纠正提示，例如 "下放中"、"塌腰，请收紧核心"。 */
    String getStatusText();

    /** 次要信息，例如 "有效 10 · 不标准 2"。 */
    String getDetailText();

    /** 结果是否已经产生（用于判断是否值得保存记录）。 */
    boolean hasResult();
}
