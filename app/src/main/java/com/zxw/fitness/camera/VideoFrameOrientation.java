package com.zxw.fitness.camera;

/** 保守判断抽帧是否仍是未旋转的编码尺寸，避免对已摆正的位图重复旋转。 */
public final class VideoFrameOrientation {
    private static final double MAX_ASPECT_ERROR = 0.03;
    private VideoFrameOrientation() { }

    /**
     * 只有 90°/270° 且实际比例明确接近编码比例、不接近摆正比例时才补转。
     * 方形、180°、未知或有非方形像素的视频无法仅凭比例确定方向，不能盲目补转。
     */
    public static boolean needsQuarterTurn(int encodedWidth, int encodedHeight, int rotationDegrees,
                                           int frameWidth, int frameHeight) {
        if ((rotationDegrees != 90 && rotationDegrees != 270)
                || encodedWidth <= 0 || encodedHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
            return false;
        }
        double encoded = encodedWidth / (double) encodedHeight;
        double upright = encodedHeight / (double) encodedWidth;
        double actual = frameWidth / (double) frameHeight;
        double encodedError = Math.abs(actual / encoded - 1d);
        double uprightError = Math.abs(actual / upright - 1d);
        return encodedError <= MAX_ASPECT_ERROR && uprightError > MAX_ASPECT_ERROR;
    }

    /** 已知宽高/旋转时检查位图是否与预期视频比例相符；未知元数据不做猜测。 */
    public static boolean matchesUprightAspect(int encodedWidth, int encodedHeight, int rotationDegrees,
                                               int frameWidth, int frameHeight) {
        if (encodedWidth <= 0 || encodedHeight <= 0 || frameWidth <= 0 || frameHeight <= 0
                || (rotationDegrees != 0 && rotationDegrees != 90
                && rotationDegrees != 180 && rotationDegrees != 270)) return true;
        boolean quarterTurn = rotationDegrees == 90 || rotationDegrees == 270;
        double expected = quarterTurn ? encodedHeight / (double) encodedWidth
                : encodedWidth / (double) encodedHeight;
        double actual = frameWidth / (double) frameHeight;
        return Math.abs(actual / expected - 1d) <= MAX_ASPECT_ERROR;
    }
}
