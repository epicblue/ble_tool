package com.zxw.fitness.camera;

import com.google.mlkit.vision.common.PointF3D;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.zxw.fitness.model.PoseFrame;

import java.util.List;

/** ML Kit 像素坐标转为相机和视频共用的、以画面宽度为基准的坐标。 */
public final class PoseFrameConverter {
    private PoseFrameConverter() { }

    public static PoseFrame fromMlKit(Pose pose, long timestampMs, int imageWidth) {
        if (pose == null) return PoseFrame.empty(timestampMs);
        List<PoseLandmark> landmarks = pose.getAllPoseLandmarks();
        if (landmarks.isEmpty()) return PoseFrame.empty(timestampMs);
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] confidence = new float[PoseFrame.LANDMARK_COUNT];
        for (PoseLandmark landmark : landmarks) {
            int type = landmark.getLandmarkType();
            if (type < 0 || type >= PoseFrame.LANDMARK_COUNT) continue;
            x[type] = landmark.getPosition().x;
            y[type] = landmark.getPosition().y;
            PointF3D position3D = landmark.getPosition3D();
            z[type] = position3D != null ? position3D.getZ() : 0f;
            confidence[type] = landmark.getInFrameLikelihood();
        }
        return PoseFrame.fromPixelCoordinates(timestampMs, x, y, z, confidence,
                Math.max(1, imageWidth));
    }
}
