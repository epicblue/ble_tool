package com.zxw.fitness.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Size;

import androidx.camera.core.ImageInfo;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.transform.OutputTransform;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.fitness.model.PoseFrame;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

/**
 * 使用真实 Android Matrix 与 CameraX 坐标变换，但 ImageProxy/预览变换为合成数据，
 * 不需要摄像头，也不声称验证了 ML Kit 或某台设备的预览。
 */
@RunWith(AndroidJUnit4.class)
@SuppressLint({"RestrictedApi", "UnsafeOptInUsageError"})
public class CameraPreviewTransformTest {
    private static final float EPS = 0.02f;

    private static ImageProxy image(int width, int height, int rotation, Rect crop) {
        ImageInfo info = (ImageInfo) Proxy.newProxyInstance(ImageInfo.class.getClassLoader(),
                new Class<?>[]{ImageInfo.class}, (proxy, method, args) -> {
                    if ("getRotationDegrees".equals(method.getName())) return rotation;
                    if ("getTimestamp".equals(method.getName())) return 123000000L;
                    return null;
                });
        return (ImageProxy) Proxy.newProxyInstance(ImageProxy.class.getClassLoader(),
                new Class<?>[]{ImageProxy.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getWidth": return width;
                        case "getHeight": return height;
                        case "getCropRect": return new Rect(crop);
                        case "getImageInfo": return info;
                        default: return null;
                    }
                });
    }

    private static float[] upright(float x, float y, int width, int height, int rotation) {
        switch (rotation) {
            case 0: return new float[]{x, y};
            case 90: return new float[]{height - y, x};
            case 180: return new float[]{width - x, height - y};
            case 270: return new float[]{y, width - x};
            default: throw new IllegalArgumentException();
        }
    }

    private static RectF uprightCrop(Rect r, int width, int height, int rotation) {
        float[] a = upright(r.left, r.top, width, height, rotation);
        float[] b = upright(r.right, r.top, width, height, rotation);
        float[] c = upright(r.left, r.bottom, width, height, rotation);
        float[] d = upright(r.right, r.bottom, width, height, rotation);
        return new RectF(Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0])),
                Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1])),
                Math.max(Math.max(a[0], b[0]), Math.max(c[0], d[0])),
                Math.max(Math.max(a[1], b[1]), Math.max(c[1], d[1])));
    }

    /**
     * 构造合成预览目标：共同视口缩放到偏移的画面区域；front 在目标中镜像。
     * OutputTransform 的构造器在 1.1.0 中对外限制，测试仅用它创建已知的模拟目标。
     */
    private static OutputTransform target(int width, int height, Rect crop, int rotation,
                                          boolean front, float left, float top, float scale) {
        RectF uprightCrop = uprightCrop(crop, width, height, rotation);
        float[] norm = {-1, -1, 1, -1, -1, 1};
        float[] raw = {crop.left, crop.top, crop.right, crop.top, crop.left, crop.bottom};
        float[] dst = new float[6];
        for (int i = 0; i < 3; i++) {
            float[] p = upright(raw[i * 2], raw[i * 2 + 1], width, height, rotation);
            float x = (p[0] - uprightCrop.left) * scale;
            dst[i * 2] = left + (front ? uprightCrop.width() * scale - x : x);
            dst[i * 2 + 1] = top + (p[1] - uprightCrop.top) * scale;
        }
        Matrix matrix = new Matrix();
        assertTrue(matrix.setPolyToPoly(norm, 0, dst, 0, 3));
        return new OutputTransform(matrix, new Size(crop.width(), crop.height()));
    }

    private static void assertRect(RectF expected, RectF actual) {
        assertEquals(expected.left, actual.left, EPS);
        assertEquals(expected.top, actual.top, EPS);
        assertEquals(expected.right, actual.right, EPS);
        assertEquals(expected.bottom, actual.bottom, EPS);
    }

    @Test public void allRotationsCropsAndFrontMirrorMapUprightFullImagePoints() {
        int[][] sizes = {{640, 480}, {1600, 1200}, {1920, 1080}};
        for (int[] size : sizes) {
            int w = size[0], h = size[1];
            for (Rect crop : new Rect[]{new Rect(0, 0, w, h),
                    new Rect(w / 10, h / 12, w * 4 / 5, h * 5 / 6)}) {
                for (int rotation : new int[]{0, 90, 180, 270}) {
                    RectF expectedCrop = uprightCrop(crop, w, h, rotation);
                    for (boolean front : new boolean[]{false, true}) {
                        float left = 73, top = 91, scale = .75f;
                        CameraPreviewTransform mapping = CameraPreviewTransform.create(
                                image(w, h, rotation, crop),
                                target(w, h, crop, rotation, front, left, top, scale), front);
                        assertNotNull(mapping);
                        assertRect(expectedCrop, mapping.copyVisibleSource());
                        // 非中心点和边缘点；输入是摆正的全图坐标，不是 crop 内相对坐标。
                        for (float[] raw : new float[][]{
                                {crop.left, crop.top}, {crop.right, crop.bottom},
                                {crop.left + crop.width() * .23f, crop.top + crop.height() * .61f}}) {
                            float[] rotated = upright(raw[0], raw[1], w, h, rotation);
                            float expectedX = (rotated[0] - expectedCrop.left) * scale;
                            if (front) expectedX = expectedCrop.width() * scale - expectedX;
                            float expectedY = (rotated[1] - expectedCrop.top) * scale;
                            float[] actual = rotated.clone();
                            mapping.copyPixelsToPreview().mapPoints(actual);
                            assertEquals("x: rotation=" + rotation + " front=" + front,
                                    left + expectedX, actual[0], EPS);
                            assertEquals("y: rotation=" + rotation + " front=" + front,
                                    top + expectedY, actual[1], EPS);
                        }
                    }
                }
            }
        }
    }

    @Test public void missingOrInvalidTransformIsNotGuessedAndCopiesAreIndependent() {
        Rect crop = new Rect(80, 40, 560, 440);
        assertNull(CameraPreviewTransform.create(image(640, 480, 90, crop), null, false));
        OutputTransform target = target(640, 480, crop, 90, false, 100, 80, 1.5f);
        assertNull(CameraPreviewTransform.create(image(640, 480, 45, crop), target, false));
        assertNull(CameraPreviewTransform.create(image(640, 480, 90, new Rect(-1, 0, 500, 400)), target, false));
        assertNull(CameraPreviewTransform.create(image(640, 480, 90, new Rect(0, 0, 0, 400)), target, false));
        assertNull("旧镜头的镜像方向不匹配时不能继续画",
                CameraPreviewTransform.create(image(640, 480, 90, crop), target, true));
        assertNull(CameraPreviewTransform.create(image(640, 480, 90, crop),
                target(640, 480, crop, 90, true, 100, 80, 1.5f), false));
        Matrix invalid = new Matrix();
        invalid.setValues(new float[]{Float.NaN, 0, 0, 0, 1, 0, 0, 0, 1});
        assertNull(CameraPreviewTransform.create(image(640, 480, 90, crop),
                new OutputTransform(invalid, new Size(crop.width(), crop.height())), false));
        invalid.setValues(new float[]{0, 0, 0, 0, 1, 0, 0, 0, 1});
        assertNull(CameraPreviewTransform.create(image(640, 480, 90, crop),
                new OutputTransform(invalid, new Size(crop.width(), crop.height())), false));
        CameraPreviewTransform mapping = CameraPreviewTransform.create(image(640, 480, 90, crop), target, false);
        assertNotNull(mapping);
        mapping.copyVisibleSource().setEmpty();
        mapping.copyPixelsToPreview().reset();
        assertRect(new RectF(40, 80, 440, 560), mapping.copyVisibleSource());
        float[] actual = {360, 200};
        mapping.copyPixelsToPreview().mapPoints(actual);
        assertEquals(580, actual[0], EPS);
        assertEquals(260, actual[1], EPS);
    }

    private static PoseFrame pose(float noseX, float noseY, float shoulderX, float shoulderY,
                                  int width, boolean pixels) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        x[PoseFrame.NOSE] = noseX; y[PoseFrame.NOSE] = noseY; c[PoseFrame.NOSE] = 1;
        x[PoseFrame.LEFT_SHOULDER] = shoulderX; y[PoseFrame.LEFT_SHOULDER] = shoulderY;
        c[PoseFrame.LEFT_SHOULDER] = 1;
        return pixels ? PoseFrame.of(1, x, y, z, c)
                : PoseFrame.fromPixelCoordinates(1, x, y, z, c, width);
    }

    private static Bitmap draw(SkeletonOverlay overlay, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        overlay.draw(new Canvas(bitmap));
        return bitmap;
    }

    @Test public void overlayUsesCameraMatrixOnceClipsViewportAndKeepsLegacyReplay() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getTargetContext();
            SkeletonOverlay overlay = new SkeletonOverlay(context);
            overlay.layout(0, 0, 1200, 1200);
            overlay.setShowDetails(false);
            Rect crop = new Rect(80, 40, 560, 440);
            CameraPreviewTransform mapping = CameraPreviewTransform.create(image(640, 480, 90, crop),
                    target(640, 480, crop, 90, true, 100, 80, 1.5f), true);
            // 两点都在完整图中，但左肩在共同视口外。
            PoseFrame frame = pose(360, 200, 470, 100, 480, false);
            overlay.setMirrored(true); // 旧回放开关不能让实时前摄二次镜像
            overlay.setPixelCoordinates(true); // 实时输入始终是按宽归一化
            overlay.setCameraFrame(frame, 480, 640, mapping, 20, 30, 10, 10);
            Bitmap live = draw(overlay, 1200, 1200);
            // 镜像后的鼻：100 + (440 - 360) * 1.5 + 10, 80 + (200 - 80) * 1.5 + 20。
            assertNotEquals(0, live.getPixel(230, 280));
            // 裁剪外的左肩不能画到预览外（如果不裁剪则是 x=65,y=130）。
            assertEquals(0, live.getPixel(65, 130));

            overlay.setCameraFrame(frame, 480, 640, null, 20, 30, 10, 10);
            assertEquals(0, draw(overlay, 1200, 1200).getPixel(825, 375));
            overlay.setCameraFrame(frame, 480, 640, mapping, 20, 30, 10, 10);
            overlay.layout(0, 0, 1100, 1100); // 布局变化后不能继续用旧矩阵
            assertEquals(0, draw(overlay, 1100, 1100).getPixel(230, 280));

            // 新实例和 setFrame() 都保留视频/旧骨架的 FIT_CENTER 路径。
            overlay.layout(0, 0, 1200, 1200);
            overlay.setMirrored(false);
            overlay.setSourceSize(480, 640);
            overlay.setPixelCoordinates(false);
            overlay.setFrame(frame);
            assertNotEquals(0, draw(overlay, 1200, 1200).getPixel(825, 375));
            overlay.setPixelCoordinates(true);
            overlay.setFrame(pose(360, 200, 470, 100, 480, true));
            assertNotEquals(0, draw(overlay, 1200, 1200).getPixel(825, 375));
        });
    }
}
