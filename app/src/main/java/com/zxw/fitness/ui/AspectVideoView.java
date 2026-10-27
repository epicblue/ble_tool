package com.zxw.fitness.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.VideoView;

/**
 * 按视频宽高比自适应尺寸的 VideoView，避免画面被拉伸变形。
 */
public class AspectVideoView extends VideoView {

    private int videoWidth;
    private int videoHeight;

    public AspectVideoView(Context context) {
        super(context);
    }

    public AspectVideoView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectVideoView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** 设置视频显示宽高比（应使用旋转之后的宽高）。 */
    public void setVideoAspect(int width, int height) {
        if (width > 0 && height > 0) {
            videoWidth = width;
            videoHeight = height;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec);
        int height = getDefaultSize(getSuggestedMinimumHeight(), heightMeasureSpec);
        if (videoWidth > 0 && videoHeight > 0 && width > 0 && height > 0) {
            if (width * videoHeight > height * videoWidth) {
                width = height * videoWidth / videoHeight;
            } else {
                height = width * videoHeight / videoWidth;
            }
        }
        setMeasuredDimension(width, height);
    }
}
