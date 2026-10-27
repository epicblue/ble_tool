package com.zxw.fitness.analysis;

import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.PoseFrame;

/** 支持按当前动作主动确认个人目标的往返计数器。 */
public interface PersonalCalibrationAnalyzer extends RepAnalyzer {
    /** 开始一组时恢复个人设置；null 使用默认标准。 */
    void setPersonalCalibration(PersonalCalibration calibration);

    /** 读取最后一帧的当前姿势；raw 用于排除滤波器沿用的丢失关键点。 */
    PersonalCalibration capturePersonalCalibration(PoseFrame raw);

    /** capture 失败的具体原因。 */
    String getCalibrationError();

    /** 应用一次已确认的按钮事件：立即计数并等待回到起始位，回放同样执行此事件。 */
    void applyCalibrationEvent(PersonalCalibration calibration);
}
