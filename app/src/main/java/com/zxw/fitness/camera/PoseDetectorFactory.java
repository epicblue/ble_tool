package com.zxw.fitness.camera;

import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;

/** 实时相机和视频重识别共用同一款 Accurate 模型和检测模式。 */
public final class PoseDetectorFactory {
    private PoseDetectorFactory() { }

    public static PoseDetector create(boolean detectPersonEveryFrame) {
        int mode = detectPersonEveryFrame ? AccuratePoseDetectorOptions.SINGLE_IMAGE_MODE
                : AccuratePoseDetectorOptions.STREAM_MODE;
        AccuratePoseDetectorOptions options = new AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(mode)
                .build();
        return PoseDetection.getClient(options);
    }
}
