package com.zxw.fitness.pose;

/** 一帧场景诊断结果；点数只表示坐标和置信度，不表示动作合格。 */
public final class PoseSceneResult {
    public enum Quality { WAITING, WARNING, USABLE }

    public final Quality quality;
    public final int reliablePoints;
    public final int totalPoints;
    public final String message;

    PoseSceneResult(Quality quality, int reliablePoints, int totalPoints, String message) {
        this.quality = quality;
        this.reliablePoints = reliablePoints;
        this.totalPoints = totalPoints;
        this.message = message;
    }
}
