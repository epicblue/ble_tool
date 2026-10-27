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

/** 把关键点和置信度标记画在预览/视频上。采用 FIT_CENTER 与画面等比对齐。 */
public class SkeletonOverlay extends View {
    private static final float DRAW_CONF = 0.3f;
    private static final int COLOR_GOOD = Color.rgb(76, 224, 179);
    private static final int COLOR_WEAK = Color.rgb(255, 176, 32);
    private static final int COLOR_PENDING = Color.rgb(220, 140, 255);

    private static final int[][] CONNECTIONS = {
            {PoseFrame.NOSE, PoseFrame.LEFT_SHOULDER}, {PoseFrame.NOSE, PoseFrame.RIGHT_SHOULDER},
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
    private final Paint weakBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pendingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private PoseFrame frame;
    private int sourceWidth = 9;
    private int sourceHeight = 16;
    private boolean mirrored;
    /** v1 旧骨架为像素坐标；新骨架的 x/y 都按源画面宽度归一化。 */
    private boolean pixelCoordinates;
    private boolean showSkeleton = true;
    private boolean showDetails = true;
    private boolean[] pendingJumps;

    public SkeletonOverlay(Context context) { this(context, null); }

    public SkeletonOverlay(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        bonePaint.setStyle(Paint.Style.STROKE);
        bonePaint.setStrokeCap(Paint.Cap.ROUND);
        bonePaint.setStrokeWidth(3.5f * density);
        bonePaint.setColor(COLOR_GOOD);
        weakBonePaint.setStyle(Paint.Style.STROKE);
        weakBonePaint.setStrokeCap(Paint.Cap.ROUND);
        weakBonePaint.setStrokeWidth(2f * density);
        weakBonePaint.setColor(0xAAFFB020);
        jointPaint.setStyle(Paint.Style.FILL);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeWidth(6f * density);
        haloPaint.setColor(Color.parseColor("#331DE9B7"));
        pendingPaint.setStyle(Paint.Style.STROKE);
        pendingPaint.setStrokeWidth(2f * density);
        pendingPaint.setColor(COLOR_PENDING);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(11f * scaledDensity);
        labelPaint.setFakeBoldText(true);
        labelStroke.setColor(Color.BLACK);
        labelStroke.setTextSize(11f * scaledDensity);
        labelStroke.setFakeBoldText(true);
        labelStroke.setStyle(Paint.Style.STROKE);
        labelStroke.setStrokeWidth(3f * density);
        labelStroke.setStrokeJoin(Paint.Join.ROUND);
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

    /** 老版骨架文件保存了原始像素，回放时使用原来的坐标单位。 */
    public void setPixelCoordinates(boolean pixels) {
        if (pixelCoordinates != pixels) {
            pixelCoordinates = pixels;
            invalidate();
        }
    }

    /** 前置摄像头预览是镜像的，骨架也要镜像以对齐。 */
    public void setMirrored(boolean mirrored) {
        this.mirrored = mirrored;
        invalidate();
    }

    public void setShowSkeleton(boolean show) {
        showSkeleton = show;
        if (!show) frame = null;
        invalidate();
    }

    /** 关闭详细标记时仍显示原本的可信骨架。 */
    public void setShowDetails(boolean show) {
        if (showDetails != show) {
            showDetails = show;
            invalidate();
        }
    }

    /** 原始点暂被滤波器拒绝时用紫圈提示；回放没有原始帧，不显示紫圈。 */
    public void setPendingJumps(@Nullable boolean[] pending) { pendingJumps = pending; }

    public void setFrame(@Nullable PoseFrame frame) {
        this.frame = frame;
        invalidate();
    }

    public PoseFrame getFrame() { return frame; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        PoseFrame f = frame;
        if (f == null || !f.isVisible() || !showSkeleton) return;
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return;
        float scale = Math.min(viewWidth / sourceWidth, viewHeight / sourceHeight);
        float offsetX = (viewWidth - sourceWidth * scale) / 2f;
        float offsetY = (viewHeight - sourceHeight * scale) / 2f;
        float unit = pixelCoordinates ? 1f : sourceWidth;
        PointF[] points = new PointF[PoseFrame.LANDMARK_COUNT];
        boolean[] weak = new boolean[PoseFrame.LANDMARK_COUNT];
        for (int lm : PoseFrame.LANDMARK_SUBSET) {
            float confidence = f.getConfidence(lm);
            if (Float.isNaN(confidence) || Float.isInfinite(confidence) || confidence > 1f
                    || confidence < (showDetails ? DRAW_CONF : 0.5f)
                    || !f.isWithinImage(lm, sourceWidth, sourceHeight, pixelCoordinates)) continue;
            float px = offsetX + f.getX(lm) * unit * scale;
            float py = offsetY + f.getY(lm) * unit * scale;
            if (mirrored) px = viewWidth - px;
            points[lm] = new PointF(px, py);
            weak[lm] = confidence < 0.5f;
        }
        for (int[] pair : CONNECTIONS) {
            PointF a = points[pair[0]], b = points[pair[1]];
            if (a == null || b == null) continue;
            if (!weak[pair[0]] && !weak[pair[1]]) {
                canvas.drawLine(a.x, a.y, b.x, b.y, haloPaint);
                canvas.drawLine(a.x, a.y, b.x, b.y, bonePaint);
            } else {
                canvas.drawLine(a.x, a.y, b.x, b.y, weakBonePaint);
            }
        }
        float radius = 4f * density;
        for (int lm : PoseFrame.LANDMARK_SUBSET) {
            PointF p = points[lm];
            if (p == null) continue;
            jointPaint.setColor(weak[lm] ? COLOR_WEAK : COLOR_GOOD);
            canvas.drawCircle(p.x, p.y, radius, jointPaint);
            boolean pending = showDetails && pendingJumps != null && lm < pendingJumps.length && pendingJumps[lm];
            if (pending) canvas.drawCircle(p.x, p.y, radius + 3f * density, pendingPaint);
            if (showDetails) {
                // 向下取整，避免 49.9% 被标为 50% 却仍显示为黄色。
                int percent = (int) (f.getConfidence(lm) * 100f);
                String text = landmarkName(lm) + " " + percent + "%"
                        + (pending ? " 待定" : "");
                float width = labelPaint.measureText(text);
                float gap = 7f * density;
                float textX = isLeft(lm) ? p.x - gap - width : p.x + gap;
                if (textX < 2f) textX = p.x + gap;
                if (textX + width > viewWidth - 2f) textX = p.x - gap - width;
                textX = Math.max(2f, Math.min(textX, viewWidth - width - 2f));
                float textY = Math.max(labelPaint.getTextSize() + 2f,
                        Math.min(p.y - 5f * density, viewHeight - 2f));
                canvas.drawText(text, textX, textY, labelStroke);
                canvas.drawText(text, textX, textY, labelPaint);
            }
        }
    }

    private static boolean isLeft(int lm) {
        return lm == PoseFrame.LEFT_SHOULDER || lm == PoseFrame.LEFT_ELBOW || lm == PoseFrame.LEFT_WRIST
                || lm == PoseFrame.LEFT_HIP || lm == PoseFrame.LEFT_KNEE || lm == PoseFrame.LEFT_ANKLE
                || lm == PoseFrame.LEFT_HEEL || lm == PoseFrame.LEFT_FOOT_INDEX;
    }

    private static String landmarkName(int lm) {
        switch (lm) {
            case PoseFrame.NOSE: return "鼻";
            case PoseFrame.LEFT_SHOULDER: return "左肩";
            case PoseFrame.RIGHT_SHOULDER: return "右肩";
            case PoseFrame.LEFT_ELBOW: return "左肘";
            case PoseFrame.RIGHT_ELBOW: return "右肘";
            case PoseFrame.LEFT_WRIST: return "左腕";
            case PoseFrame.RIGHT_WRIST: return "右腕";
            case PoseFrame.LEFT_HIP: return "左髋";
            case PoseFrame.RIGHT_HIP: return "右髋";
            case PoseFrame.LEFT_KNEE: return "左膝";
            case PoseFrame.RIGHT_KNEE: return "右膝";
            case PoseFrame.LEFT_ANKLE: return "左踝";
            case PoseFrame.RIGHT_ANKLE: return "右踝";
            case PoseFrame.LEFT_HEEL: return "左跟";
            case PoseFrame.RIGHT_HEEL: return "右跟";
            case PoseFrame.LEFT_FOOT_INDEX: return "左脚";
            case PoseFrame.RIGHT_FOOT_INDEX: return "右脚";
            default: return "点";
        }
    }
}
