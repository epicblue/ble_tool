package com.zxw.fitness.ui;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * 轻量视频播放视图：{@link TextureView} + {@link MediaPlayer}。
 *
 * <p>之所以不用系统 {@code VideoView}：{@code VideoView} 不暴露 {@code setPlaybackParams}，
 * 无法做 0.5x / 1.5x / 2x 倍速回放。这里自己管理 MediaPlayer，对外提供与 VideoView 类似的
 * 少量接口（setVideoPath / start / pause / seekTo / stopPlayback），并额外支持倍速与按比例测量。</p>
 */
public class PoseVideoView extends FrameLayout {

    private final TextureView textureView;

    private MediaPlayer mediaPlayer;
    private MediaPlayer.OnPreparedListener preparedListener;
    private MediaPlayer.OnErrorListener errorListener;

    private String pendingPath;
    private int pendingSeekMs = -1;
    private boolean prepared;
    private boolean autoPlay;
    private float speed = 1f;
    private int videoWidth;
    private int videoHeight;

    public PoseVideoView(Context context) {
        this(context, null);
    }

    public PoseVideoView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PoseVideoView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        textureView = new TextureView(context);
        LayoutParams params = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.CENTER;
        addView(textureView, params);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                openPlayer();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                // 尺寸变化由 onMeasure 处理
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                releasePlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                // 不需要处理
            }
        });
    }

    // ---- 对外接口（尽量与 VideoView 保持一致，方便替换） ----

    public void setVideoPath(String path) {
        pendingPath = path;
        pendingSeekMs = -1;
        autoPlay = false;
        prepared = false;
        if (textureView.isAvailable()) {
            openPlayer();
        }
        // 否则等 onSurfaceTextureAvailable 回调时自动创建播放器
    }

    public void setOnPreparedListener(MediaPlayer.OnPreparedListener listener) {
        this.preparedListener = listener;
    }

    public void setOnErrorListener(MediaPlayer.OnErrorListener listener) {
        this.errorListener = listener;
    }

    public void start() {
        autoPlay = true;
        if (mediaPlayer != null && prepared) {
            try {
                mediaPlayer.start();
                applySpeed();
            } catch (IllegalStateException ignored) {
                // 播放器状态异常时忽略，下一帧 tick 会重新判断
            }
        }
    }

    public void pause() {
        autoPlay = false;
        if (mediaPlayer != null && prepared) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (IllegalStateException ignored) {
                // ignore
            }
        }
    }

    public void seekTo(int positionMs) {
        if (mediaPlayer != null && prepared) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mediaPlayer.seekTo((long) positionMs, MediaPlayer.SEEK_CLOSEST);
                } else {
                    mediaPlayer.seekTo(positionMs);
                }
                return;
            } catch (IllegalStateException ignored) {
                // 回退到待处理
            }
        }
        pendingSeekMs = positionMs;
    }

    public int getCurrentPosition() {
        if (mediaPlayer != null && prepared) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (IllegalStateException ignored) {
                return 0;
            }
        }
        return pendingSeekMs >= 0 ? pendingSeekMs : 0;
    }

    public int getDuration() {
        if (mediaPlayer != null && prepared) {
            try {
                return mediaPlayer.getDuration();
            } catch (IllegalStateException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public boolean isPlaying() {
        if (mediaPlayer != null && prepared) {
            try {
                return mediaPlayer.isPlaying();
            } catch (IllegalStateException ignored) {
                return false;
            }
        }
        return false;
    }

    /** 设置播放倍速（0.5x ~ 2x），Android 6.0 以下忽略。 */
    public void setSpeed(float speed) {
        this.speed = speed;
        applySpeed();
    }

    public float getSpeed() {
        return speed;
    }

    public void stopPlayback() {
        autoPlay = false;
        pendingSeekMs = -1;
        pendingPath = null;
        releasePlayer();
    }

    /** 设置视频显示宽高比（应使用旋转之后的宽高），避免画面被拉伸。 */
    public void setVideoAspect(int width, int height) {
        if (width > 0 && height > 0) {
            videoWidth = width;
            videoHeight = height;
            requestLayout();
        }
    }

    // ---- 内部实现 ----

    private void openPlayer() {
        releasePlayer();
        if (pendingPath == null || !textureView.isAvailable()) {
            return;
        }
        final SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        if (surfaceTexture == null) {
            return;
        }
        final MediaPlayer player = new MediaPlayer();
        mediaPlayer = player;
        prepared = false;
        try {
            Surface surface = new Surface(surfaceTexture);
            try {
                player.setSurface(surface);
            } finally {
                surface.release();
            }
            player.setDataSource(pendingPath);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    if (mediaPlayer != mp) {
                        return;
                    }
                    prepared = true;
                    videoWidth = mp.getVideoWidth();
                    videoHeight = mp.getVideoHeight();
                    requestLayout();
                    if (pendingSeekMs >= 0) {
                        int position = pendingSeekMs;
                        pendingSeekMs = -1;
                        seekTo(position);
                    }
                    if (autoPlay) {
                        try {
                            mp.start();
                            applySpeed();
                        } catch (IllegalStateException ignored) {
                            // ignore
                        }
                    }
                    if (preparedListener != null) {
                        preparedListener.onPrepared(mp);
                    }
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    if (mediaPlayer != mp) return true;
                    prepared = false;
                    final MediaPlayer failed = mp;
                    // 不在回调里直接 release，避免状态混乱
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (mediaPlayer == failed) {
                                releasePlayer();
                            }
                        }
                    });
                    if (errorListener != null) {
                        return errorListener.onError(mp, what, extra);
                    }
                    return true;
                }
            });
            player.prepareAsync();
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            releasePlayer();
            if (errorListener != null) {
                errorListener.onError(player, MediaPlayer.MEDIA_ERROR_UNKNOWN, 0);
            }
        }
    }

    private void applySpeed() {
        if (mediaPlayer == null || !prepared || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            // setPlaybackParams 可能自动开始播放；暂停时只记住倍速，等 start() 后应用。
            if (!mediaPlayer.isPlaying() && !autoPlay) return;
            PlaybackParams params = new PlaybackParams();
            params.setSpeed(speed);
            mediaPlayer.setPlaybackParams(params);
        } catch (RuntimeException ignored) {
            // 设备不支持倍速
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
            } catch (RuntimeException ignored) {
                // ignore
            }
            try {
                mediaPlayer.release();
            } catch (RuntimeException ignored) {
                // ignore
            }
            mediaPlayer = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releasePlayer();
        super.onDetachedFromWindow();
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
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
