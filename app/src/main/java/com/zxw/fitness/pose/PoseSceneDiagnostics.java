package com.zxw.fitness.pose;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;

/**
 * 基于当前关键点的场景提示：只解释遮挡、出画和尺度，不改变运动成绩，也不推断动作是否安全。
 * 实时输入是 ML Kit 原始帧；回放可输入新识别原始帧、新滤波帧或录制滤波帧。
 */
public final class PoseSceneDiagnostics {
    private static final int SHOULDER = 0, ELBOW = 1, WRIST = 2, HIP = 3, KNEE = 4, ANKLE = 5;
    private static final int[][] JOINTS = {
            {PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER},
            {PoseFrame.LEFT_ELBOW, PoseFrame.RIGHT_ELBOW},
            {PoseFrame.LEFT_WRIST, PoseFrame.RIGHT_WRIST},
            {PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP},
            {PoseFrame.LEFT_KNEE, PoseFrame.RIGHT_KNEE},
            {PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE}
    };
    private static final String[] NAMES = {"肩", "肘", "手腕", "髋", "膝", "脚踝"};
    private static final int[] PUSH_PARTS = {SHOULDER, ELBOW, WRIST, HIP, KNEE, ANKLE};
    private static final int[] LEG_PARTS = {SHOULDER, HIP, KNEE, ANKLE};
    private static final int[] PULL_PARTS = {SHOULDER, ELBOW, WRIST, HIP};
    private static final int[] PLANK_PARTS = {SHOULDER, WRIST, HIP, ANKLE};
    private static final int[] HOLD_PARTS = {SHOULDER, HIP, ANKLE};
    private static final float MIN_TORSO_WIDTH = 0.07f;
    private static final float EDGE_FRACTION = 0.025f;

    private PoseSceneDiagnostics() { }

    /** pixels 为 true 时读取旧版 v1 像素日志；新日志及相机帧 x/y 均按画面宽归一化。 */
    public static PoseSceneResult inspect(PoseFrame frame, ExerciseType type, int width, int height, boolean pixels) {
        if (width <= 0 || height <= 0) return new PoseSceneResult(PoseSceneResult.Quality.WAITING, 0, JOINTS.length * 2, "等待画面尺寸");
        if (frame == null || !frame.isVisible()) {
            return new PoseSceneResult(PoseSceneResult.Quality.WAITING, 0, JOINTS.length * 2, "未检测到人体，请面向相机并保持光线充足");
        }
        if (type == null) type = ExerciseType.PUSHUP;
        int count = 0;
        for (int[] pair : JOINTS) {
            if (good(frame, pair[0], width, height, pixels)) count++;
            if (good(frame, pair[1], width, height, pixels)) count++;
        }
        int[] required = requiredParts(type);
        StringBuilder missing = new StringBuilder();
        boolean anyOutside = false;
        for (int part : required) {
            int left = JOINTS[part][0], right = JOINTS[part][1];
            boolean l = good(frame, left, width, height, pixels);
            boolean r = good(frame, right, width, height, pixels);
            boolean needBoth = type == ExerciseType.RUNNING && part == ANKLE;
            if ((!l && !r) || (needBoth && !(l && r))) {
                if (missing.length() > 0) missing.append('、');
                missing.append(needBoth ? "双脚踝" : NAMES[part]);
                anyOutside |= (!l && outside(frame, left, width, height, pixels))
                        || (!r && outside(frame, right, width, height, pixels));
            }
        }
        if (missing.length() > 0) {
            return new PoseSceneResult(PoseSceneResult.Quality.WARNING, count, JOINTS.length * 2, missing + (anyOutside
                    ? "可能出画，请后退或调整手机" : "识别不足，请避开遮挡并改善光线"));
        }
        boolean leftTorso = sideComplete(frame, width, height, pixels, 0, SHOULDER, HIP);
        boolean rightTorso = sideComplete(frame, width, height, pixels, 1, SHOULDER, HIP);
        if (!leftTorso && !rightTorso) {
            return new PoseSceneResult(PoseSceneResult.Quality.WARNING, count, JOINTS.length * 2, "肩和髋需要同一侧同时入镜");
        }
        if (type == ExerciseType.PUSHUP || type == ExerciseType.CHAIR_PUSHUP
                || type == ExerciseType.BAD_PUSHUP || type == ExerciseType.PULLUP) {
            if (!sideComplete(frame, width, height, pixels, 0, SHOULDER, ELBOW, WRIST)
                    && !sideComplete(frame, width, height, pixels, 1, SHOULDER, ELBOW, WRIST)) {
                return new PoseSceneResult(PoseSceneResult.Quality.WARNING, count, JOINTS.length * 2, "同侧肩、肘、腕未完整入镜，角度可能不稳定");
            }
        } else if (type == ExerciseType.SQUAT || type == ExerciseType.WALL_SIT) {
            if (!sideComplete(frame, width, height, pixels, 0, HIP, KNEE, ANKLE)
                    && !sideComplete(frame, width, height, pixels, 1, HIP, KNEE, ANKLE)) {
                return new PoseSceneResult(PoseSceneResult.Quality.WARNING, count, JOINTS.length * 2, "同侧髋、膝、踝未完整入镜，角度可能不稳定");
            }
        }
        // 单侧仍可参与判定，但已明确出画的另一侧也值得提示。
        for (int part : required) {
            for (int side = 0; side < 2; side++) {
                int lm = JOINTS[part][side];
                if (outside(frame, lm, width, height, pixels)) {
                    return new PoseSceneResult(PoseSceneResult.Quality.WARNING, count, JOINTS.length * 2,
                            (side == 0 ? "左" : "右") + NAMES[part] + "可能出画，动作时注意保持入镜");
                }
            }
        }
        int side = leftTorso ? 0 : 1;
        int shoulder = JOINTS[SHOULDER][side], hip = JOINTS[HIP][side];
        float dx = frame.getX(shoulder) - frame.getX(hip);
        float dy = frame.getY(shoulder) - frame.getY(hip);
        float torso = (float) Math.sqrt(dx * dx + dy * dy) / (pixels ? width : 1f);
        if (torso < MIN_TORSO_WIDTH) {
            return new PoseSceneResult(PoseSceneResult.Quality.WARNING, count, JOINTS.length * 2, "人体较小，建议靠近相机并保持全身入镜");
        }
        int nearEdge = 0;
        for (int part : required) {
            for (int lm : JOINTS[part]) {
                if (!good(frame, lm, width, height, pixels)) continue;
                float x = frame.getX(lm) / (pixels ? width : 1f);
                float y = frame.getY(lm) / (pixels ? height : height / (float) width);
                if (x < EDGE_FRACTION || x > 1f - EDGE_FRACTION
                        || y < EDGE_FRACTION || y > 1f - EDGE_FRACTION) nearEdge++;
            }
        }
        if (nearEdge >= 2) {
            return new PoseSceneResult(PoseSceneResult.Quality.WARNING, count, JOINTS.length * 2, "关键点靠近画面边缘，动作时可能出框");
        }
        return new PoseSceneResult(PoseSceneResult.Quality.USABLE, count, JOINTS.length * 2, "主要关键点可用，仍需检查动作姿势");
    }

    private static int[] requiredParts(ExerciseType type) {
        if (type == ExerciseType.PUSHUP || type == ExerciseType.CHAIR_PUSHUP
                || type == ExerciseType.BAD_PUSHUP) return PUSH_PARTS;
        if (type == ExerciseType.SQUAT || type == ExerciseType.WALL_SIT) return LEG_PARTS;
        if (type == ExerciseType.PULLUP) return PULL_PARTS;
        if (type == ExerciseType.PLANK) return PLANK_PARTS;
        return HOLD_PARTS; // 倒立及跑步；跑步另外要求两侧脚踝
    }

    private static boolean good(PoseFrame frame, int lm, int width, int height, boolean pixels) {
        float confidence = frame.getConfidence(lm);
        return frame.isReliable(lm, PoseMath.MIN_CONFIDENCE) && confidence <= 1f
                && frame.isWithinImage(lm, width, height, pixels);
    }

    private static boolean outside(PoseFrame frame, int lm, int width, int height, boolean pixels) {
        return frame.getConfidence(lm) >= 0.3f && !frame.isWithinImage(lm, width, height, pixels);
    }

    private static boolean sideComplete(PoseFrame frame, int width, int height, boolean pixels,
                                        int side, int... parts) {
        for (int part : parts) {
            if (!good(frame, JOINTS[part][side], width, height, pixels)) return false;
        }
        return true;
    }
}
