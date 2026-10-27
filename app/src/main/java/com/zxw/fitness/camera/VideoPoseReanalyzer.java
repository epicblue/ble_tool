package com.zxw.fitness.camera;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetector;
import com.zxw.fitness.analysis.VideoPoseReanalysis;
import com.zxw.fitness.analysis.VideoReanalysisRunner;
import com.zxw.fitness.analysis.VideoReanalysisRunner.ImageFrame;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 后台顺序抽取视频画面并运行与实时相机相同的 ML Kit Accurate 模型。 */
public final class VideoPoseReanalyzer {
    private static final String TAG = "VideoPoseReanalyzer";
    private static final int MAX_IMAGE_SIDE = 960;

    public interface Listener {
        void onProgress(int complete, int total);
        void onCompleted(VideoPoseReanalysis result);
        void onError(String message);
    }

    private static final class Job implements VideoReanalysisRunner.Cancellation {
        volatile boolean cancelled;
        volatile Listener listener;
        Job(Listener listener) { this.listener = listener; }
        @Override public boolean isCancelled() { return cancelled; }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // start/cancel/dispatch 均在主线程；任务只读取自己的 volatile cancelled。
    private Job current;
    private boolean closed;

    public void start(final File file, final Session session, Listener listener) {
        if (closed) throw new IllegalStateException("视频识别器已关闭");
        cancel();
        final Job job = new Job(listener);
        current = job;
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    VideoReanalysisRunner.Factory<Bitmap> factory = new VideoReanalysisRunner.Factory<Bitmap>() {
                        @Override public VideoReanalysisRunner.FrameSource<Bitmap> openSource() throws Exception {
                            return new AndroidFrameSource(file);
                        }
                        @Override public VideoReanalysisRunner.PoseEngine<Bitmap> openEngine(boolean everyFrame) {
                            return new MlKitEngine(PoseDetectorFactory.create(everyFrame));
                        }
                    };
                    VideoPoseReanalysis result = VideoReanalysisRunner.run(factory, session, job,
                            new VideoReanalysisRunner.Progress() {
                                private int lastPercent = -1;
                                @Override public void onProgress(final int complete, final int total) {
                                    int percent = complete * 100 / total;
                                    if (percent == lastPercent || job.cancelled) return;
                                    lastPercent = percent;
                                    mainHandler.post(new Runnable() {
                                        @Override public void run() {
                                            if (current == job && !job.cancelled && job.listener != null) {
                                                job.listener.onProgress(complete, total);
                                            }
                                        }
                                    });
                                }
                            });
                    deliverResult(job, result);
                } catch (VideoReanalysisRunner.Cancelled ignored) {
                    // 切换记录或离开页面后，旧任务不再通知界面。
                } catch (OutOfMemoryError error) {
                    Log.e(TAG, "视频识别内存不足", error);
                    deliverError(job, "设备内存不足，无法重新识别；可回放录制骨架");
                } catch (Exception error) {
                    Log.w(TAG, "视频重新识别失败", error);
                    String detail = error instanceof IOException && error.getMessage() != null
                            ? error.getMessage() : "无法解码或识别视频画面";
                    deliverError(job, detail);
                }
            }
        });
    }

    private void deliverResult(final Job job, final VideoPoseReanalysis result) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (current != job || job.cancelled) return;
                current = null;
                Listener listener = job.listener;
                job.listener = null;
                if (listener != null) listener.onCompleted(result);
            }
        });
    }

    private void deliverError(final Job job, final String message) {
        mainHandler.post(new Runnable() {
            @Override public void run() {
                if (current != job || job.cancelled) return;
                current = null;
                Listener listener = job.listener;
                job.listener = null;
                if (listener != null) listener.onError(message);
            }
        });
    }

    /** 当前 ML Kit 调用结束后释放其图像与检测器；不让旧任务覆盖新页面。 */
    public void cancel() {
        Job job = current;
        current = null;
        if (job != null) {
            job.cancelled = true;
            job.listener = null;
        }
    }

    public void shutdown() {
        cancel();
        closed = true;
        // 不 interrupt 正在处理 Bitmap 的 ML Kit；该帧完成后由任务自行释放。
        worker.shutdown();
    }

    private static final class AndroidFrameSource implements VideoReanalysisRunner.FrameSource<Bitmap> {
        private final MediaMetadataRetriever retriever;
        private final long duration;
        private final int encodedWidth;
        private final int encodedHeight;
        private final int rotation;

        AndroidFrameSource(File file) throws IOException {
            retriever = new MediaMetadataRetriever();
            long parsed;
            int width, height, degrees;
            try {
                if (file == null || !file.isFile() || file.length() == 0) {
                    throw new IOException("原视频不存在");
                }
                retriever.setDataSource(file.getAbsolutePath());
                String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                parsed = value == null ? 0 : Long.parseLong(value);
                width = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                height = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                degrees = metadataInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            } catch (Exception error) {
                releaseRetriever(retriever);
                if (error instanceof IOException) throw (IOException) error;
                throw new IOException("无法读取原视频", error);
            } catch (Error error) {
                releaseRetriever(retriever);
                throw error;
            }
            duration = parsed;
            encodedWidth = width;
            encodedHeight = height;
            rotation = (degrees % 360 + 360) % 360;
        }

        private static int metadataInt(MediaMetadataRetriever retriever, int key) {
            try {
                String value = retriever.extractMetadata(key);
                return value == null ? 0 : Integer.parseInt(value);
            } catch (RuntimeException ignored) {
                return 0;
            }
        }

        @Override public long durationMs() { return duration; }

        @Override public ImageFrame<Bitmap> frameAt(long timestampMs) throws IOException {
            long timeUs = timestampMs * 1000L;
            Bitmap bitmap = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    MediaMetadataRetriever.BitmapParams params = new MediaMetadataRetriever.BitmapParams();
                    params.setPreferredConfig(Bitmap.Config.ARGB_8888);
                    bitmap = retriever.getScaledFrameAtTime(timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST, MAX_IMAGE_SIDE, MAX_IMAGE_SIDE, params);
                } catch (RuntimeException ignored) {
                    // 继续尝试不带 BitmapParams 的接口。
                }
            }
            if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                try {
                    // 让系统等比缩放；常见 Android 实现已按元数据摆正。
                    // 如果尺寸明确表明某厂商仍返回未旋转画面，下面才补转。
                    bitmap = retriever.getScaledFrameAtTime(timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST, MAX_IMAGE_SIDE, MAX_IMAGE_SIDE);
                } catch (RuntimeException ignored) {
                    // 部分解码器不支持缩放抽帧，下面尝试普通抽帧。
                }
            }
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            }
            if (bitmap == null) return null;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int max = Math.max(width, height);
            if (max > MAX_IMAGE_SIDE) {
                int scaledWidth = Math.max(1, Math.round(width * (MAX_IMAGE_SIDE / (float) max)));
                int scaledHeight = Math.max(1, Math.round(height * (MAX_IMAGE_SIDE / (float) max)));
                Bitmap original = bitmap;
                try {
                    bitmap = Bitmap.createScaledBitmap(original, scaledWidth, scaledHeight, true);
                } catch (RuntimeException | Error error) {
                    original.recycle();
                    throw error;
                }
                if (original != bitmap) original.recycle();
            }
            if (VideoFrameOrientation.needsQuarterTurn(encodedWidth, encodedHeight, rotation,
                    bitmap.getWidth(), bitmap.getHeight())) {
                Bitmap original = bitmap;
                try {
                    Matrix matrix = new Matrix();
                    matrix.postRotate(rotation);
                    bitmap = Bitmap.createBitmap(original, 0, 0, original.getWidth(),
                            original.getHeight(), matrix, true);
                } catch (RuntimeException | Error error) {
                    original.recycle();
                    throw error;
                }
                if (original != bitmap) original.recycle();
            }
            if (!VideoFrameOrientation.matchesUprightAspect(encodedWidth, encodedHeight, rotation,
                    bitmap.getWidth(), bitmap.getHeight())) {
                bitmap.recycle();
                throw new IOException("视频抽帧与录像比例不一致，无法可靠叠加骨架");
            }
            // Retriever 可能给出 RGB_565 或硬件 Bitmap；统一成 ML Kit 更通用的像素格式。
            if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
                Bitmap original = bitmap;
                try {
                    bitmap = original.copy(Bitmap.Config.ARGB_8888, false);
                } catch (RuntimeException | Error error) {
                    original.recycle();
                    throw error;
                }
                if (original != bitmap) original.recycle();
                if (bitmap == null) throw new IllegalStateException("无法转换视频画面格式");
            }
            return new BitmapFrame(bitmap);
        }

        @Override public void close() { releaseRetriever(retriever); }
    }

    private static void releaseRetriever(MediaMetadataRetriever retriever) {
        try { retriever.release(); } catch (Exception ignored) { }
    }

    private static final class BitmapFrame implements ImageFrame<Bitmap> {
        private final Bitmap bitmap;
        BitmapFrame(Bitmap bitmap) { this.bitmap = bitmap; }
        @Override public Bitmap image() { return bitmap; }
        @Override public int width() { return bitmap.getWidth(); }
        @Override public int height() { return bitmap.getHeight(); }
        @Override public void close() { if (!bitmap.isRecycled()) bitmap.recycle(); }
    }

    private static final class MlKitEngine implements VideoReanalysisRunner.PoseEngine<Bitmap> {
        private final PoseDetector detector;
        MlKitEngine(PoseDetector detector) { this.detector = detector; }

        @Override public PoseFrame detect(ImageFrame<Bitmap> frame,
                                          long timestampMs) throws Exception {
            Task<Pose> task = detector.process(InputImage.fromBitmap(frame.image(), 0));
            boolean interrupted = false;
            try {
                // 即使线程被中断，也要等 ML Kit 不再使用 Bitmap 后才能回收它。
                for (;;) {
                    try {
                        Pose pose = Tasks.await(task);
                        return PoseFrameConverter.fromMlKit(pose, timestampMs, frame.width());
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        @Override public void close() {
            try { detector.close(); } catch (RuntimeException ignored) { }
        }
    }
}
