package com.zxw.fitness.model;

/** 训练中在某个已录制帧之后点击校准，供历史回放按相同顺序执行。 */
public final class CalibrationChange {
    public final int frameIndex;
    public final PersonalCalibration calibration;

    public CalibrationChange(int frameIndex, PersonalCalibration calibration) {
        this.frameIndex = frameIndex;
        this.calibration = calibration;
    }
}
