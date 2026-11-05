package com.zxw.fitness.camera;

import android.annotation.SuppressLint;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.transform.CoordinateTransform;
import androidx.camera.view.transform.ImageProxyTransformFactory;
import androidx.camera.view.transform.OutputTransform;

/** 一帧 ML Kit 摆正后的全图像素坐标到 PreviewView 坐标的变换。 */
@SuppressLint("UnsafeOptInUsageError") // CameraX 1.1 的坐标变换 API 是实验性的。
public final class CameraPreviewTransform {
    private final Matrix pixelsToPreview;
    private final RectF visibleSource;

    private CameraPreviewTransform(Matrix pixelsToPreview, RectF visibleSource) {
        this.pixelsToPreview = new Matrix(pixelsToPreview);
        this.visibleSource = new RectF(visibleSource);
    }

    /**
     * 必须在 ImageProxy 仍有效时调用；target 必须来自同一 UseCaseGroup 的 PreviewView。
     * ML Kit 接收的是 MediaImage 全图和旋转角，并没有把坐标原点移到 cropRect。
     * 因此工厂启用旋转、不启用 crop-relative 坐标；cropRect 仍用于关联共同视口。
     * PreviewView 的 target 已包含前摄镜像，不要再额外镜像。镜像方向不匹配时
     * 可能仍是上一个镜头的旧 target，拒绝绘制并等待新预览。
     */
    @Nullable
    public static CameraPreviewTransform create(@NonNull ImageProxy image,
                                                @Nullable OutputTransform target,
                                                boolean frontCamera) {
        if (target == null) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        int rotation = image.getImageInfo().getRotationDegrees();
        Rect crop = image.getCropRect();
        if (width <= 0 || height <= 0 || crop == null || crop.left < 0 || crop.top < 0
                || crop.right > width || crop.bottom > height
                || crop.left >= crop.right || crop.top >= crop.bottom) return null;
        RectF uprightCrop;
        switch (rotation) {
            case 0:
                uprightCrop = new RectF(crop.left, crop.top, crop.right, crop.bottom);
                break;
            case 90:
                uprightCrop = new RectF(height - crop.bottom, crop.left,
                        height - crop.top, crop.right);
                break;
            case 180:
                uprightCrop = new RectF(width - crop.right, height - crop.bottom,
                        width - crop.left, height - crop.top);
                break;
            case 270:
                uprightCrop = new RectF(crop.top, width - crop.right,
                        crop.bottom, width - crop.left);
                break;
            default:
                return null;
        }
        ImageProxyTransformFactory factory = new ImageProxyTransformFactory();
        factory.setUsingCropRect(false);
        factory.setUsingRotationDegrees(true);
        OutputTransform source = factory.getOutputTransform(image);
        Matrix matrix = new Matrix();
        new CoordinateTransform(source, target).transform(matrix);
        float[] values = new float[9];
        matrix.getValues(values);
        for (float value : values) {
            if (Float.isNaN(value) || Float.isInfinite(value)) return null;
        }
        double determinant = (double) values[Matrix.MSCALE_X] * values[Matrix.MSCALE_Y]
                - (double) values[Matrix.MSKEW_X] * values[Matrix.MSKEW_Y];
        if (Math.abs(determinant) < 1e-12 || (determinant < 0) != frontCamera) return null;
        return new CameraPreviewTransform(matrix, uprightCrop);
    }

    /** 返回副本，调用方可将 PreviewView 原点平移到叠加层原点。 */
    @NonNull
    public Matrix copyPixelsToPreview() { return new Matrix(pixelsToPreview); }

    /** 摆正后的分析图中，真正被共同视口显示的区域（像素）。 */
    @NonNull
    public RectF copyVisibleSource() { return new RectF(visibleSource); }
}
