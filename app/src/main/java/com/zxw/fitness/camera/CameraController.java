package com.zxw.fitness.camera;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetector;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.GravityAlign;
import com.zxw.fitness.pose.FrozenPoseRecovery;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相机 + 姿态识别 + 录像的封装。
 *
 * <p>同时使用三个用例：Preview 显示画面、ImageAnalysis 送入 ML Kit 识别姿态、
 * VideoCapture 录制 mp4 供回放。三个用例尽量使用相同画面比例；裁剪、镜像和回放对齐仍需真机验证。</p>
 */
public class CameraController {

    private static final String TAG = "CameraController";

    public interface Listener {
        /**
         * 每识别一帧回调一次。
         *
         * @param frame        重力对齐后的 33 个关键点，供判定与录制
         * @param displayFrame 未旋转的关键点，供与预览画面对齐
         * @param uprightWidth 摆正后画面宽度（像素）
         * @param uprightHeight 摆正后画面高度（像素）
         * @param rollDegrees  本帧施加的重力补偿角，供回放恢复画面坐标
         */
        void onPose(@NonNull PoseFrame frame, @NonNull PoseFrame displayFrame,
                    int uprightWidth, int uprightHeight, float rollDegrees);

        /** 仅在可疑冻结时尝试重新找人；false 表示候选不完整或未改善。 */
        default void onPoseRecovery(boolean accepted) { }

        void onCameraError(@NonNull String message);

        void onRecordingSaved(@NonNull File file, long durationMs);

        void onRecordingError(@NonNull String message);
    }

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final PreviewView previewView;
    private final Listener listener;
    /** 只为深蹲启用严格的冻结检测；平时仍使用连续跟踪。 */
    private final boolean recoverFrozenPose;
    private final FrozenPoseRecovery recovery = new FrozenPoseRecovery();

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService videoExecutor = Executors.newSingleThreadExecutor();
    /** 录像回调发生在后台线程，统一切回主线程再通知界面。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile DetectorSlot detectorSlot;
    private long cameraGeneration;
    private ProcessCameraProvider cameraProvider;
    private ImageAnalysis imageAnalysis;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private long recordingStartMs;

    private boolean frontCamera;
    private boolean started;
    private int uprightWidth;
    private int uprightHeight;

    /** 加速度计（用于横滚补偿）。 */
    private SensorManager sensorManager;
    private Sensor accelerometer;
    /** 最近一次算出的横滚补偿角（度），见 {@link GravityAlign}。 */
    private volatile float rollDegrees;
    /** 重力在画面平面上的投影是否可用（手机接近平放时为 false）。 */
    private volatile boolean rollReliable;

    private final SensorEventListener gravityListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            float[] values = event.values;
            if (values == null || values.length < 2) {
                return;
            }
            rollReliable = GravityAlign.isReliable(values[0], values[1]);
            rollDegrees = GravityAlign.isReliable(values[0], values[1])
                    ? GravityAlign.rollDegrees(values[0], values[1])
                    : 0f;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // 精度变化不影响补偿（低通已在传感器层完成）
        }
    };

    public CameraController(Context context, LifecycleOwner owner, PreviewView previewView,
                            Listener listener) {
        this(context, owner, previewView, listener, false);
    }

    public CameraController(Context context, LifecycleOwner owner, PreviewView previewView,
                            Listener listener, boolean recoverFrozenPose) {
        this.context = context.getApplicationContext();
        this.lifecycleOwner = owner;
        this.previewView = previewView;
        this.listener = listener;
        this.recoverFrozenPose = recoverFrozenPose;
    }

    public boolean isFrontCamera() {
        return frontCamera;
    }

    public int getSourceWidth() {
        return uprightWidth;
    }

    public int getSourceHeight() {
        return uprightHeight;
    }

    /** 当前横滚补偿角（度）：0 表示手机是正的。 */
    public float getRollDegrees() {
        return rollDegrees;
    }

    /** 横滚是否可信（手机接近平放时不可信，界面可提示"把手机竖起来"）。 */
    public boolean isRollReliable() {
        return rollReliable;
    }

    /** 启动相机（需要已获得 CAMERA 权限）。 */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        final long generation = ++cameraGeneration;
        startGravitySensor();
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);
        future.addListener(new Runnable() {
            @Override
            public void run() {
                if (!started || generation != cameraGeneration) return;
                try {
                    bindUseCases(future.get());
                } catch (Exception e) {
                    Log.e(TAG, "无法启动相机", e);
                    started = false;
                    if (listener != null) {
                        listener.onCameraError("无法启动相机：" + e.getMessage());
                    }
                }
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /** 切换前后摄像头，切换后需要重新绑定。 */
    public void setFrontCamera(boolean front) {
        if (frontCamera == front) {
            return;
        }
        frontCamera = front;
        if (started) {
            started = false;
            release();
            start();
        }
    }

    private void bindUseCases(ProcessCameraProvider provider) {
        cameraProvider = provider;
        provider.unbindAll();

        if (detectorSlot == null) {
            detectorSlot = new DetectorSlot(createPoseDetector());
        }

        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(frontCamera
                        ? CameraSelector.LENS_FACING_FRONT
                        : CameraSelector.LENS_FACING_BACK)
                .build();

        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build();
        imageAnalysis.setAnalyzer(analysisExecutor, new PoseAnalyzerImpl());

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD,
                        androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        try {
            provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis, videoCapture);
        } catch (Exception e) {
            // 部分设备不支持"预览 + 分析 + 录像"三路同时运行，退化为预览 + 分析
            Log.w(TAG, "三路同时绑定失败，退化为预览 + 姿态分析", e);
            videoCapture = null;
            try {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis);
            } catch (Exception e2) {
                started = false;
                if (listener != null) {
                    listener.onCameraError("相机不可用：" + e2.getMessage());
                }
            }
        }
    }

    private PoseDetector createPoseDetector() {
        // 所有动作均恢复为连续跟踪；新的 STREAM 检测器首次处理时会重新找人。
        return PoseDetectorFactory.create(false);
    }

    /** 开始录像，返回 false 表示当前设备不支持录制或未就绪。 */
    public boolean startRecording(File outputFile) {
        if (videoCapture == null) {
            return false;
        }
        if (recording != null) {
            return true;
        }
        try {
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            FileOutputOptions options = new FileOutputOptions.Builder(outputFile).build();
            recordingStartMs = System.currentTimeMillis();
            recording = videoCapture.getOutput()
                    .prepareRecording(context, options)
                    .start(videoExecutor, new androidx.core.util.Consumer<VideoRecordEvent>() {
                        @Override
                        public void accept(VideoRecordEvent event) {
                            if (event instanceof VideoRecordEvent.Finalize) {
                                final VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                                final File file = outputFile;
                                final long duration = System.currentTimeMillis() - recordingStartMs;
                                recording = null;
                                final boolean failed = fin.hasError();
                                final int errorCode = fin.getError();
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (listener == null) {
                                            return;
                                        }
                                        if (failed) {
                                            listener.onRecordingError("录像失败，错误码 " + errorCode);
                                        } else {
                                            listener.onRecordingSaved(file, duration);
                                        }
                                    }
                                });
                            }
                        }
                    });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "开始录像失败", e);
            recording = null;
            if (listener != null) {
                listener.onRecordingError("开始录像失败：" + e.getMessage());
            }
            return false;
        }
    }

    public boolean isRecording() {
        return recording != null;
    }

    /** 停止录像。文件回调通过 {@link Listener#onRecordingSaved(File, long)} 通知。 */
    public void stopRecording() {
        if (recording != null) {
            try {
                recording.stop();
            } catch (Exception e) {
                Log.w(TAG, "停止录像异常", e);
                recording = null;
            }
        }
    }

    /** 释放相机与识别器。 */
    public void stop() {
        started = false;
        cameraGeneration++;
        stopRecording();
        stopGravitySensor();
        release();
    }

    /** 注册加速度计：横滚补偿需要重力方向。传感器不可用时静默退化为不补偿。 */
    private void startGravitySensor() {
        if (sensorManager != null) {
            return;
        }
        try {
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager == null) {
                return;
            }
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer == null) {
                Log.w(TAG, "设备没有加速度计，横滚补偿不可用");
                return;
            }
            sensorManager.registerListener(gravityListener, accelerometer,
                    SensorManager.SENSOR_DELAY_GAME);
        } catch (Exception e) {
            Log.w(TAG, "注册加速度计失败，横滚补偿不可用", e);
            sensorManager = null;
        }
    }

    private void stopGravitySensor() {
        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(gravityListener);
            } catch (Exception ignored) {
                // ignore
            }
            sensorManager = null;
        }
        rollDegrees = 0f;
        rollReliable = false;
    }

    /** 页面销毁时调用：释放相机、识别器并关闭线程池。 */
    public void destroy() {
        stop();
        analysisExecutor.shutdownNow();
        videoExecutor.shutdownNow();
    }

    /** 在途任务持有租约，切镜头/离开页面时不能提前关闭正在用图像的检测器。 */
    private static final class DetectorSlot {
        final PoseDetector detector;
        private int users;
        private boolean retired;
        DetectorSlot(PoseDetector detector) { this.detector = detector; }
        synchronized boolean acquire() {
            if (retired) return false;
            users++;
            return true;
        }
        synchronized void release() {
            users--;
            if (retired && users == 0) closeDetector();
        }
        synchronized void retire() {
            if (retired) return;
            retired = true;
            if (users == 0) closeDetector();
        }
        private void closeDetector() {
            try { detector.close(); } catch (RuntimeException e) { Log.w(TAG, "关闭姿态检测器失败", e); }
        }
    }

    private void release() {
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }
        DetectorSlot slot = detectorSlot;
        detectorSlot = null;
        if (slot != null) slot.retire();
        recovery.reset();
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /** 根据原始画面输出重力对齐帧；诊断仍显示原始结果。主线程调用。 */
    private void deliver(PoseFrame displayFrame, int width, int height) {
        PoseFrame boundedFrame = displayFrame.maskOutOfFrame(width, height);
        float roll = rollDegrees;
        PoseFrame frame = Math.abs(roll) < GravityAlign.DEAD_ZONE_DEG
                ? boundedFrame : GravityAlign.rotate(boundedFrame, roll,
                0.5f, height / (2f * Math.max(1, width)));
        uprightWidth = width;
        uprightHeight = height;
        if (listener != null) listener.onPose(frame, displayFrame, width, height, roll);
    }

    private boolean isCurrent(DetectorSlot slot) {
        return started && detectorSlot == slot;
    }

    /** 同一帧无论在哪个异步分支结束，都只释放一次图像与检测器租约。 */
    private static final class FrameLease {
        final DetectorSlot slot;
        final ImageProxy image;
        private boolean finished;
        FrameLease(DetectorSlot slot, ImageProxy image) { this.slot = slot; this.image = image; }
        synchronized void finish() {
            if (finished) return;
            finished = true;
            try { slot.release(); } finally { image.close(); }
        }
    }

    private void notifyRecovery(boolean accepted) {
        if (listener == null) return;
        try { listener.onPoseRecovery(accepted); }
        catch (RuntimeException e) { Log.w(TAG, "无法显示重新定位提示", e); }
    }

    /** ImageAnalysis 分析器：普通帧只调用一次 STREAM；异常时才试用新的 STREAM。 */
    private class PoseAnalyzerImpl implements ImageAnalysis.Analyzer {
        @Override public void analyze(@NonNull final ImageProxy imageProxy) {
            final DetectorSlot slot = detectorSlot;
            if (slot == null || !slot.acquire()) {
                imageProxy.close();
                return;
            }
            final FrameLease lease = new FrameLease(slot, imageProxy);
            try {
                final int rotation = imageProxy.getImageInfo().getRotationDegrees();
                final long timestampMs = imageProxy.getImageInfo().getTimestamp() / 1000000L;
                final int imageWidth = imageProxy.getWidth();
                final int imageHeight = imageProxy.getHeight();
                final int width = rotation % 180 == 0 ? imageWidth : imageHeight;
                final int height = rotation % 180 == 0 ? imageHeight : imageWidth;
                final InputImage input = InputImage.fromMediaImage(imageProxy.getImage(), rotation);
                slot.detector.process(input).addOnCompleteListener(
                        new com.google.android.gms.tasks.OnCompleteListener<Pose>() {
                    @Override public void onComplete(@NonNull com.google.android.gms.tasks.Task<Pose> task) {
                        boolean retryStarted = false;
                        try {
                            if (!isCurrent(slot)) return;
                            if (!task.isSuccessful()) {
                                // 检测失败不能跨这帧累积“持续冻结”的证据。
                                recovery.observe(null, width, height);
                                Log.w(TAG, "姿态识别失败", task.getException());
                                return;
                            }
                            PoseFrame original = PoseFrameConverter.fromMlKit(task.getResult(), timestampMs, width);
                            if (recoverFrozenPose && recovery.observe(original, width, height)) {
                                retryStarted = true;
                                retryWithFreshStream(lease, input, original, width, height);
                            } else {
                                deliver(original, width, height);
                            }
                        } catch (RuntimeException e) {
                            recovery.observe(null, width, height);
                            Log.w(TAG, "处理姿态识别结果失败", e);
                        } finally {
                            if (!retryStarted) lease.finish();
                        }
                    }
                });
            } catch (RuntimeException e) {
                Log.w(TAG, "无法提交姿态识别", e);
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (isCurrent(slot)) recovery.observe(null, 0, 0);
                    }
                });
                lease.finish();
            }
        }
    }

    /** 保持同一 ImageProxy 有效直到候选完成；失败或只有腿时不替换旧检测器。 */
    private void retryWithFreshStream(final FrameLease lease, InputImage input,
                                      final PoseFrame original, final int width, final int height) {
        final DetectorSlot oldSlot = lease.slot;
        DetectorSlot created = null;
        try {
            created = new DetectorSlot(createPoseDetector());
            final DetectorSlot candidate = created;
            candidate.acquire();
            candidate.detector.process(input).addOnCompleteListener(
                    new com.google.android.gms.tasks.OnCompleteListener<Pose>() {
                @Override public void onComplete(@NonNull com.google.android.gms.tasks.Task<Pose> task) {
                    boolean accepted = false;
                    PoseFrame result = original;
                    try {
                        if (isCurrent(oldSlot) && task.isSuccessful()) {
                            PoseFrame fresh = PoseFrameConverter.fromMlKit(task.getResult(),
                                    original.getTimestampMs(), width);
                            if (recovery.accepts(original, fresh, width, height)) {
                                detectorSlot = candidate;
                                oldSlot.retire();
                                result = fresh;
                                accepted = true;
                            }
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "候选姿态不可用，保留原有跟踪", e);
                    } finally {
                        if (!accepted) candidate.retire();
                        candidate.release();
                    }
                    try {
                        if ((accepted && isCurrent(candidate)) || (!accepted && isCurrent(oldSlot))) {
                            notifyRecovery(accepted);
                            if (isCurrent(accepted ? candidate : oldSlot)) deliver(result, width, height);
                        }
                    } catch (RuntimeException e) {
                        Log.w(TAG, "无法显示重新定位结果", e);
                    } finally {
                        lease.finish();
                    }
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "重新定位失败，继续原有跟踪", e);
            if (created != null) {
                created.retire();
                created.release();
            }
            try {
                if (isCurrent(oldSlot)) {
                    notifyRecovery(false);
                    if (isCurrent(oldSlot)) deliver(original, width, height);
                }
            } catch (RuntimeException callbackError) {
                Log.w(TAG, "无法显示原有跟踪结果", callbackError);
            } finally {
                lease.finish();
            }
        }
    }
}
