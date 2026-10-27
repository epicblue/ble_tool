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
import com.google.mlkit.vision.common.PointF3D;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.GravityAlign;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 相机 + 姿态识别 + 录像的封装。
 *
 * <p>同时使用三个用例：Preview 显示画面、ImageAnalysis 送入 ML Kit 识别姿态、
 * VideoCapture 录制 mp4 供回放。三个用例统一使用 16:9，保证骨架与画面严格对齐。</p>
 */
public class CameraController {

    private static final String TAG = "CameraController";

    public interface Listener {
        /**
         * 每识别一帧回调一次。
         *
         * @param frame        33 个归一化关键点
         * @param uprightWidth 摆正后画面宽度（像素）
         * @param uprightHeight 摆正后画面高度（像素）
         */
        void onPose(@NonNull PoseFrame frame, int uprightWidth, int uprightHeight);

        void onCameraError(@NonNull String message);

        void onRecordingSaved(@NonNull File file, long durationMs);

        void onRecordingError(@NonNull String message);
    }

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final PreviewView previewView;
    private final Listener listener;

    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService videoExecutor = Executors.newSingleThreadExecutor();
    /** 录像回调发生在后台线程，统一切回主线程再通知界面。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private PoseDetector poseDetector;
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
        this.context = context.getApplicationContext();
        this.lifecycleOwner = owner;
        this.previewView = previewView;
        this.listener = listener;
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
        startGravitySensor();
        final ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(context);
        future.addListener(new Runnable() {
            @Override
            public void run() {
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

        if (poseDetector == null) {
            poseDetector = createPoseDetector();
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
        // 实时模式：准确度优先（FULL 模型）以保证俯卧撑/倒立判定稳定
        AccuratePoseDetectorOptions options = new AccuratePoseDetectorOptions.Builder()
                .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
                .build();
        return PoseDetection.getClient(options);
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

    private void release() {
        if (cameraProvider != null) {
            try {
                cameraProvider.unbindAll();
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
            imageAnalysis = null;
        }
        if (poseDetector != null) {
            poseDetector.close();
            poseDetector = null;
        }
    }

    /** ImageAnalysis 分析器：把图像送入 ML Kit 并转换关键点。 */
    private class PoseAnalyzerImpl implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull ImageProxy imageProxy) {
            final PoseDetector detector = poseDetector;
            if (detector == null) {
                imageProxy.close();
                return;
            }
            final int rotation = imageProxy.getImageInfo().getRotationDegrees();
            final long timestampMs = imageProxy.getImageInfo().getTimestamp() / 1000000L;
            final int imageWidth = imageProxy.getWidth();
            final int imageHeight = imageProxy.getHeight();
            InputImage inputImage;
            try {
                inputImage = InputImage.fromMediaImage(imageProxy.getImage(), rotation);
            } catch (Exception e) {
                imageProxy.close();
                return;
            }
            detector.process(inputImage)
                    .addOnSuccessListener(new com.google.android.gms.tasks.OnSuccessListener<Pose>() {
                        @Override
                        public void onSuccess(Pose pose) {
                            // 横滚补偿：手机歪着拿时把关键点转正，判定与录制的骨架都在这套坐标里
                            PoseFrame frame = GravityAlign.align(toPoseFrame(pose, timestampMs),
                                    rollDegrees);
                            int w = rotation % 180 == 0 ? imageWidth : imageHeight;
                            int h = rotation % 180 == 0 ? imageHeight : imageWidth;
                            uprightWidth = w;
                            uprightHeight = h;
                            if (listener != null) {
                                listener.onPose(frame, w, h);
                            }
                        }
                    })
                    .addOnFailureListener(new com.google.android.gms.tasks.OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            Log.w(TAG, "姿态识别失败", e);
                        }
                    })
                    .addOnCompleteListener(new com.google.android.gms.tasks.OnCompleteListener<Pose>() {
                        @Override
                        public void onComplete(@NonNull com.google.android.gms.tasks.Task<Pose> task) {
                            imageProxy.close();
                        }
                    });
        }
    }

    private static PoseFrame toPoseFrame(Pose pose, long timestampMs) {
        if (pose == null) {
            return PoseFrame.empty(timestampMs);
        }
        List<PoseLandmark> landmarks = pose.getAllPoseLandmarks();
        if (landmarks.isEmpty()) {
            return PoseFrame.empty(timestampMs);
        }
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] confidence = new float[PoseFrame.LANDMARK_COUNT];
        for (PoseLandmark landmark : landmarks) {
            int type = landmark.getLandmarkType();
            if (type < 0 || type >= PoseFrame.LANDMARK_COUNT) {
                continue;
            }
            x[type] = landmark.getPosition().x;
            y[type] = landmark.getPosition().y;
            // PointF3D 只提供 getter（PointF 才是公有字段），这里统一用方法访问
            PointF3D position3D = landmark.getPosition3D();
            z[type] = position3D != null ? position3D.getZ() : 0f;
            confidence[type] = landmark.getInFrameLikelihood();
        }
        return PoseFrame.of(timestampMs, x, y, z, confidence);
    }
}
