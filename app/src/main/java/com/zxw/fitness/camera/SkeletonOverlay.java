package com.zxw.fitness.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.zxw.fitness.model.PoseFrame;

/**
 * 把识别出的关键点画成骨架，覆盖在预览/视频之上。
 *
 * <p>使用 FIT_CENTER 的方式把归一化坐标映射到控件区域，保证与相机预览画面严格对齐。</p>
 */
public class SkeletonOverlay extends View {

    private static final int[][] CONNECTIONS = {
            {PoseFrame.NOSE, PoseFrame.LEFT_SHOULDER},
            {PoseFrame.NOSE, PoseFrame.RIGHT_SHOULDER},
            {PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER},
            {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW},
            {PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST},
            {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW},
            {PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST},
            {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_HIP},
            {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_HIP},
            {PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP},
            {PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE},
            {PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE},
            {PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE},
            {PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE},
            {PoseFrame.LEFT_ANKLE, PoseFrame.LEFT_HEEL},
            {PoseFrame.RIGHT_ANKLE, PoseFrame.RIGHT_HEEL},
            {PoseFrame.LEFT_HEEL, PoseFrame.LEFT_FOOT_INDEX},
            {PoseFrame.RIGHT_HEEL, PoseFrame.RIGHT_FOOT_INDEX}
    };

    private final Paint bonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private PoseFrame frame;
    private int sourceWidth = 9;
    private int sourceHeight = 16;
    private boolean mirrored;
    private boolean showSkeleton = true;

    public SkeletonOverlay(Context context) {
        this(context, null);
    }

    public SkeletonOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        bonePaint.setStyle(Paint.Style.STROKE);
        bonePaint.setStrokeCap(Paint.Cap.ROUND);
        bonePaint.setStrokeWidth(3.5f * density);
        bonePaint.setColor(Color.parseColor("#4CE0B3"));

        jointPaint.setStyle(Paint.Style.FILL);
        jointPaint.setColor(Color.parseColor("#FFFFFF"));

        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(6f * density);
        haloPaint.setColor(Color.parseColor("#331DE9B7"));
        setWillNotDraw(false);
    }

    /** 设置归一化坐标对应的原始画面尺寸（摆正后的宽高）。 */
    public void setSourceSize(int width, int height) {
        if (width > 0 && height > 0 && (width != sourceWidth || height != sourceHeight)) {
            sourceWidth = width;
            sourceHeight = height;
            invalidate();
        }
    }

    /** 前置摄像头预览是镜像的，骨架也要镜像以对齐。 */
    public void setMirrored(boolean mirrored) {
        this.mirrored = mirrored;
        invalidate();
    }

    public void setShowSkeleton(boolean show) {
        this.showSkeleton = show;
        if (!show) {
            frame = null;
        }
        invalidate();
    }

    public void setFrame(@Nullable PoseFrame frame) {
        this.frame = frame;
        invalidate();
    }

    public PoseFrame getFrame() {
        return frame;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        PoseFrame f = frame;
        if (f == null || !f.isVisible() || !showSkeleton) {
            return;
        }
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        float scale = Math.min(viewWidth / sourceWidth, viewHeight / sourceHeight);
        float offsetX = (viewWidth - sourceWidth * scale) / 2f;
        float offsetY = (viewHeight - sourceHeight * scale) / 2f;

        PointF[] points = new PointF[PoseFrame.LANDMARK_COUNT];
        for (int lm : PoseFrame.LANDMARK_SUBSET) {
            if (!f.isReliable(lm)) {
                continue;
            }
            float px = offsetX + f.getX(lm) * sourceWidth * scale;
            float py = offsetY + f.getY(lm) * sourceHeight * scale;
            if (mirrored) {
                px = viewWidth - px;
            }
            points[lm] = new PointF(px, py);
        }

        for (int[] pair : CONNECTIONS) {
            PointF a = points[pair[0]];
            PointF b = points[pair[1]];
            if (a == null || b == null) {
                continue;
            }
            canvas.drawLine(a.x, a.y, b.x, b.y, haloPaint);
            canvas.drawLine(a.x, a.y, b.x, b.y, bonePaint);
        }

        float radius = 4f * getResources().getDisplayMetrics().density;
        for (int lm : PoseFrame.LANDMARK_SUBSET) {
            PointF p = points[lm];
            if (p != null) {
                canvas.drawCircle(p.x, p.y, radius, jointPaint);
            }
        }
    }
}
