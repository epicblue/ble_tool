package com.zxw.fitness.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.zxw.R;
import com.zxw.fitness.analysis.HoldAnalyzer;
import com.zxw.fitness.analysis.RepAnalyzer;
import com.zxw.fitness.analysis.RepEvent;
import com.zxw.fitness.analysis.WorkoutRecorder;
import com.zxw.fitness.camera.CameraController;
import com.zxw.fitness.camera.SkeletonOverlay;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;
import com.zxw.fitness.util.TimeUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * 训练页：相机预览 + 骨架叠加 + 实时计数/计时，结束后保存视频与骨架数据。
 */
public class TrainingActivity extends Activity implements LifecycleOwner,
        CameraController.Listener, WorkoutRecorder.Listener {

    public static final String EXTRA_EXERCISE = "exercise";
    public static final String EXTRA_VARIANT = "variant";

    private static final int REQ_CAMERA = 201;
    private static final long TICK_MS = 100;

    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private PreviewView previewView;
    private SkeletonOverlay overlay;
    private TextView tvScore;
    private TextView tvUnit;
    private TextView tvStatus;
    private TextView tvDetail;
    private TextView tvBadge;
    private TextView tvElapsed;
    private TextView tvRec;
    private TextView btnStart;
    private TextView btnFinish;
    private TextView btnCalibrate;
    private TextView tvCalibration;
    private ProgressBar progress;
    private View hintBox;
    private TextView tvHintTitle;
    private TextView tvHintBody;

    private ExerciseType exerciseType;
    private String variant;
    private UserProfile user;

    private CameraController camera;
    private WorkoutRecorder recorder;
    private Session session;

    private boolean running;
    private boolean finishSilent;
    private boolean sessionSaved;
    private boolean resultDialogShown;
    private boolean videoRequested;
    private File videoFile;
    private File thumbFile;
    private long lastRecBlink;
    private int calibrationCountdown;

    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_training);
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        exerciseType = ExerciseType.fromKey(getIntent().getStringExtra(EXTRA_EXERCISE));
        variant = getIntent().getStringExtra(EXTRA_VARIANT);
        user = UserStore.getCurrent(this);

        bindViews();
        setupUi();
        setupCamera();
    }

    private void bindViews() {
        previewView = findViewById(R.id.previewView);
        overlay = findViewById(R.id.overlay);
        tvScore = findViewById(R.id.tvScore);
        tvUnit = findViewById(R.id.tvUnit);
        tvStatus = findViewById(R.id.tvStatus);
        tvDetail = findViewById(R.id.tvDetail);
        tvBadge = findViewById(R.id.tvBadge);
        tvElapsed = findViewById(R.id.tvElapsed);
        tvRec = findViewById(R.id.tvRec);
        btnStart = findViewById(R.id.btnStart);
        btnFinish = findViewById(R.id.btnFinish);
        btnCalibrate = findViewById(R.id.btnCalibrate);
        tvCalibration = findViewById(R.id.tvCalibration);
        progress = findViewById(R.id.progress);
        hintBox = findViewById(R.id.hintBox);
        tvHintTitle = findViewById(R.id.tvHintTitle);
        tvHintBody = findViewById(R.id.tvHintBody);
    }

    private void setupUi() {
        ((TextView) findViewById(R.id.tvTitle)).setText(getTitleText());
        tvUnit.setText(exerciseType.getUnitLabel());
        tvScore.setText(exerciseType.isTimerExercise() ? "0.0" : "0");
        tvBadge.setVisibility(exerciseType.isTimerExercise() ? View.GONE : View.VISIBLE);
        tvBadge.setText("不标准 0");
        tvHintTitle.setText(getTitleText());
        String calibrationHelp = exerciseType.supportsPersonalCalibration()
                ? "\n\n个人校准：先回到起始位，再保持在当前能做到的最好位置，点击「个人校准 +1」。"
                + "立即记一次并保存目标；回到起始位后继续。独自训练可长按按钮，5 秒后自动采样。" : "";
        tvHintBody.setText(exerciseType.getDescription() + "\n\n"
                + "手机竖直固定，整个人保持在画面内；光线充足、背景干净识别更准。"
                + calibrationHelp);
        updateCalibrationIndicator();
        tvStatus.setText("点击「开始训练」");
        progress.setMax(100);
        progress.setProgress(0);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) {
                    confirmQuit();
                } else {
                    finish();
                }
            }
        });
        findViewById(R.id.btnSwitchCamera).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) {
                    Toast.makeText(TrainingActivity.this, "训练中不能切换镜头，请先结束本次训练",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (camera != null) {
                    camera.setFrontCamera(!camera.isFrontCamera());
                    overlay.setMirrored(camera.isFrontCamera());
                }
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startWorkout();
            }
        });
        btnCalibrate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (calibrationCountdown > 0) {
                    cancelCalibrationCountdown();
                    Toast.makeText(TrainingActivity.this, "已取消倒计时校准", Toast.LENGTH_SHORT).show();
                } else {
                    calibrateNow();
                }
            }
        });
        btnCalibrate.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (!running || recorder == null) return true;
                cancelCalibrationCountdown();
                calibrationCountdown = 5;
                btnCalibrate.setText("5 秒后校准 · 点按取消");
                Toast.makeText(TrainingActivity.this,
                        "5 秒内先回到起始位，再到最佳位置并保持；再点按钮可取消。",
                        Toast.LENGTH_LONG).show();
                handler.postDelayed(calibrationTick, 1000);
                return true;
            }
        });
        btnFinish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWorkout(false);
            }
        });
    }

    private final Runnable calibrationTick = new Runnable() {
        @Override public void run() {
            if (!running || recorder == null || calibrationCountdown <= 0) {
                cancelCalibrationCountdown();
                return;
            }
            calibrationCountdown--;
            if (calibrationCountdown == 0) {
                btnCalibrate.setText("个人校准 +1");
                calibrateNow();
            } else {
                btnCalibrate.setText(calibrationCountdown + " 秒后校准 · 点按取消");
                handler.postDelayed(this, 1000);
            }
        }
    };

    private void cancelCalibrationCountdown() {
        handler.removeCallbacks(calibrationTick);
        calibrationCountdown = 0;
        if (btnCalibrate != null) btnCalibrate.setText("个人校准 +1");
    }

    private void calibrateNow() {
        if (!running || recorder == null) return;
        String error = recorder.calibratePersonal();
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            return;
        }
        UserProfile updated = UserStore.getById(this, user.getId());
        if (updated != null) user = updated;
        updateCalibrationIndicator();
        updateLiveUi();
        Toast.makeText(this, "已计 1 次并保存个人目标；请先回到起始位。个人目标不代表标准姿势。",
                Toast.LENGTH_LONG).show();
    }

    private void updateCalibrationIndicator() {
        if (!exerciseType.supportsPersonalCalibration()) {
            tvCalibration.setVisibility(View.GONE);
            return;
        }
        tvCalibration.setVisibility(View.VISIBLE);
        PersonalCalibration calibration = user.getCalibration(exerciseType, variant);
        tvCalibration.setText(calibration == null
                ? "标准目标 · 可在训练中校准当前最佳动作"
                : "个人目标已启用 · 可在账号设置中查看或恢复默认");
    }

    private String getTitleText() {
        if (exerciseType == ExerciseType.CHAIR_PUSHUP) {
            boolean hands = "hands".equals(variant);
            return "折凳俯卧撑 · " + (hands ? "手撑凳" : "脚垫凳");
        }
        return exerciseType.getTitle();
    }

    private void setupCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            tvHintTitle.setText("需要摄像头权限");
            tvHintBody.setText("动作识别完全在手机本地完成，画面不会上传。\n请允许使用摄像头后继续。");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }
        startCamera();
    }

    private void startCamera() {
        if (camera != null) {
            return;
        }
        camera = new CameraController(this, this, previewView, this);
        camera.start();
        overlay.setMirrored(camera.isFrontCamera());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "没有摄像头权限无法进行动作捕捉", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    // ---- 训练流程 ----

    private void startWorkout() {
        if (running) {
            return;
        }
        if (camera == null) {
            Toast.makeText(this, "相机还没准备好，请稍候再试", Toast.LENGTH_SHORT).show();
            return;
        }
        UserProfile updated = UserStore.getById(this, user.getId());
        if (updated == null) {
            Toast.makeText(this, "账号不存在，请返回后重新选择", Toast.LENGTH_LONG).show();
            return;
        }
        user = updated;
        updateCalibrationIndicator();
        session = new Session();
        session.setId(SessionStore.newSessionId());
        session.setUserId(user.getId());
        session.setExerciseType(exerciseType);
        session.setVariant(variant == null ? "" : variant);
        session.setInitialCalibration(user.getCalibration(exerciseType, variant));
        session.setStartedAt(System.currentTimeMillis());
        recorder = new WorkoutRecorder(this, session, true, this);

        videoRequested = false;
        if (camera != null) {
            videoFile = new File(SessionStore.mediaDir(this, user.getId()),
                    session.getId() + ".mp4");
            videoRequested = camera.startRecording(videoFile);
        }

        running = true;
        finishSilent = false;
        sessionSaved = false;
        resultDialogShown = false;
        btnStart.setVisibility(View.GONE);
        btnCalibrate.setVisibility(exerciseType.supportsPersonalCalibration() ? View.VISIBLE : View.GONE);
        btnFinish.setVisibility(View.VISIBLE);
        tvRec.setVisibility(videoRequested ? View.VISIBLE : View.GONE);
        hintBox.setVisibility(View.GONE);
        tvStatus.setText("开始！");
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            updateLiveUi();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void updateLiveUi() {
        if (recorder == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - session.getStartedAt();
        tvElapsed.setText(TimeUtils.formatStopwatch(elapsed));

        if (exerciseType.isTimerExercise()) {
            HoldAnalyzer hold = recorder.getHoldAnalyzer();
            if (hold == null) {
                return;
            }
            long best = hold.getBestHoldMs();
            long current = hold.getCurrentHoldMs();
            tvScore.setText(String.format(Locale.US, "%.1f", best / 1000.0));
            tvUnit.setText("秒");
            int percent = (int) Math.min(100, current * 100 / 60000);
            progress.setProgress(percent);
            String extra = hold.getExtraText();
            tvDetail.setText(hold.getDetailText()
                    + (extra == null || extra.length() == 0 ? "" : " · " + extra));
            tvStatus.setText(hold.getStatusText());
        } else {
            RepAnalyzer reps = recorder.getRepAnalyzer();
            if (reps == null) {
                return;
            }
            tvScore.setText(String.valueOf(exerciseType == ExerciseType.BAD_PUSHUP
                    ? reps.getBadReps() : reps.getGoodReps()));
            tvUnit.setText(exerciseType.getUnitLabel());
            progress.setProgress((int) (reps.getDepthProgress() * 100));
            tvStatus.setText(reps.getStatusText());
            tvDetail.setText(reps.getDetailText());
            tvBadge.setText("不标准 " + reps.getBadReps());
            tvBadge.setVisibility(reps.getBadReps() > 0 ? View.VISIBLE : View.GONE);
        }

        if (videoRequested && camera != null && camera.isRecording()) {
            long now = System.currentTimeMillis();
            if (now - lastRecBlink > 800) {
                lastRecBlink = now;
                tvRec.setText(tvRec.getText().toString().contains("●") ? "○ REC" : "● REC");
            }
        }
    }

    private void confirmQuit() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("结束本次训练？")
                .setMessage("当前进度会被保存到历史记录中。")
                .setPositiveButton("结束并保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finishWorkout(false);
                    }
                })
                .setNegativeButton("继续训练", null)
                .show();
    }

    private void finishWorkout(boolean silent) {
        if (!running) {
            return;
        }
        running = false;
        cancelCalibrationCountdown();
        handler.removeCallbacks(tick);
        tvRec.setVisibility(View.GONE);
        btnFinish.setVisibility(View.GONE);
        btnCalibrate.setVisibility(View.GONE);
        btnStart.setVisibility(View.VISIBLE);
        btnStart.setText("再来一组");

        if (recorder == null) {
            return;
        }
        saveThumbnail();
        recorder.finish(System.currentTimeMillis());
        if (videoFile != null) {
            session.setVideoFile(videoRequested ? videoFile.getName() : null);
        }
        if (camera != null && videoRequested) {
            camera.stopRecording();
        }

        if (!recorder.hasResult()) {
            // 没有任何有效动作：不写入历史记录，避免垃圾数据
            deleteFiles();
            if (!silent) {
                new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                        .setTitle("没有识别到动作")
                        .setMessage("本次没有检测到完整的动作，未保存记录。\n\n可以检查：是否全身入镜、光线是否充足、是否侧对镜头。")
                        .setPositiveButton("知道了", null)
                        .show();
            } else {
                Toast.makeText(this, "未识别到动作，本次未保存", Toast.LENGTH_SHORT).show();
            }
            resetUi();
            return;
        }

        // 视频需要一点时间写完；先等 finalize 回调，最多等 1.5 秒再兜底保存
        finishSilent = silent;
        if (videoRequested && camera != null) {
            handler.postDelayed(saveRunnable, 1500);
        } else {
            saveSessionIfNeeded();
        }
    }

    private final Runnable saveRunnable = new Runnable() {
        @Override
        public void run() {
            saveSessionIfNeeded();
        }
    };

    private void saveThumbnail() {
        if (previewView == null || session == null) {
            return;
        }
        Bitmap bitmap = previewView.getBitmap();
        if (bitmap == null) {
            return;
        }
        try {
            int targetWidth = 480;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            Bitmap scaled = width > targetWidth
                    ? Bitmap.createScaledBitmap(bitmap, targetWidth,
                    Math.max(1, height * targetWidth / width), true)
                    : bitmap;
            thumbFile = new File(SessionStore.mediaDir(this, user.getId()),
                    session.getId() + ".jpg");
            FileOutputStream out = new FileOutputStream(thumbFile);
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out);
            out.close();
            session.setThumbFile(thumbFile.getName());
            if (scaled != bitmap) {
                scaled.recycle();
            }
        } catch (IOException | OutOfMemoryError ignored) {
            thumbFile = null;
        }
    }

    private void saveSessionIfNeeded() {
        if (sessionSaved || session == null) {
            return;
        }
        sessionSaved = true;
        handler.removeCallbacks(saveRunnable);
        if (videoRequested && videoFile != null) {
            // 视频可能还在写入，先记录文件名；若最终失败，播放页会自动回退到骨架回放
            session.setVideoFile(videoFile.getName());
        }
        SessionStore.save(this, session);
        if (finishSilent) {
            Toast.makeText(this, "已保存到历史记录", Toast.LENGTH_SHORT).show();
            resetUi();
            return;
        }
        showResultDialog();
    }

    private void showResultDialog() {
        if (resultDialogShown || isFinishing()) {
            return;
        }
        resultDialogShown = true;
        final Session saved = session;
        StringBuilder message = new StringBuilder();
        message.append("结果：").append(saved.getResultText())
                .append(saved.hasPersonalCalibration() ? "（含个人目标计数）" : "").append("\n");
        message.append("用时：").append(TimeUtils.formatDurationCn(saved.getDurationMs())).append("\n");
        if (exerciseType == ExerciseType.RUNNING) {
            message.append(String.format(Locale.CHINA, "共 %d 步\n", saved.getReps()));
        } else if (!exerciseType.isTimerExercise()) {
            message.append(String.format(Locale.CHINA, "有效 %d 次 · 不标准 %d 次\n",
                    saved.getReps(), saved.getBadReps()));
        } else if (exerciseType == ExerciseType.WALL_HANDSTAND) {
            message.append(saved.isWallContact() ? "检测到贴墙支撑\n" : "未检测到明显贴墙\n");
        }
        message.append("时间：").append(TimeUtils.formatFriendly(saved.getStartedAt()));

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("训练完成")
                .setMessage(message.toString())
                .setPositiveButton("查看回放", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent intent = new Intent(TrainingActivity.this, ReplayActivity.class);
                        intent.putExtra(ReplayActivity.EXTRA_SESSION_ID, saved.getId());
                        intent.putExtra(ReplayActivity.EXTRA_USER_ID, saved.getUserId());
                        startActivity(intent);
                        finish();
                    }
                })
                .setNegativeButton("完成", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void deleteFiles() {
        File[] files = {videoFile, thumbFile};
        for (File file : files) {
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        File pose = session == null ? null : SessionStore.resolveMedia(this, user.getId(),
                SessionStore.mediaPrefix(session.getId()) + "_pose.dat");
        if (pose != null && pose.exists()) {
            //noinspection ResultOfMethodCallIgnored
            pose.delete();
        }
    }

    private void resetUi() {
        tvScore.setText(exerciseType.isTimerExercise() ? "0.0" : "0");
        tvStatus.setText("点击「开始训练」");
        tvDetail.setText(exerciseType.isTimerExercise()
                ? "本组 0.0 秒 · 共 0 组" : "有效 0 · 不标准 0");
        tvBadge.setText("不标准 0");
        tvBadge.setVisibility(View.GONE);
        progress.setProgress(0);
        tvElapsed.setText("00:00");
        hintBox.setVisibility(View.VISIBLE);
        tvHintTitle.setText(getTitleText());
        tvHintBody.setText("可以调整手机位置后再来一组，或者返回查看历史记录。");
        recorder = null;
        session = null;
        videoFile = null;
    }

    // ---- 生命周期 ----

    @Override
    protected void onStart() {
        super.onStart();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME);
    }

    @Override
    public void onBackPressed() {
        if (running) {
            confirmQuit();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE);
        if (running) {
            finishWorkout(true);
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        // 兜底：录像 finalize 回调还没到就退出时，避免丢掉本次成绩
        if (!sessionSaved && session != null && recorder != null && recorder.hasResult()) {
            sessionSaved = true;
            SessionStore.save(this, session);
        }
        if (camera != null) {
            camera.destroy();
            camera = null;
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        super.onDestroy();
    }

    // ---- CameraController.Listener ----

    @Override
    public void onPose(@NonNull PoseFrame frame, @NonNull PoseFrame displayFrame,
                       int uprightWidth, int uprightHeight, float rollDegrees) {
        if (overlay != null) {
            overlay.setSourceSize(uprightWidth, uprightHeight);
            overlay.setPixelCoordinates(false);
            overlay.setFrame(displayFrame);
        }
        if (running && recorder != null) {
            recorder.onPose(frame, uprightWidth, uprightHeight, rollDegrees);
        }
    }

    @Override
    public void onCameraError(@NonNull String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRecordingSaved(@NonNull File file, long durationMs) {
        if (session == null) {
            // 训练已无有效动作，清掉这次录像，避免残留文件
            if (!sessionSaved && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            return;
        }
        session.setVideoFile(file.getName());
        if (sessionSaved) {
            SessionStore.save(this, session);
        } else {
            saveSessionIfNeeded();
        }
    }

    @Override
    public void onRecordingError(@NonNull String message) {
        videoRequested = false;
        if (session != null) {
            session.setVideoFile(null);
            if (sessionSaved) {
                SessionStore.save(this, session);
            }
        }
        Toast.makeText(this, message + "\n本次改为仅保存骨架回放", Toast.LENGTH_LONG).show();
        saveSessionIfNeeded();
    }

    // ---- WorkoutRecorder.Listener ----

    @Override
    public void onRep(RepEvent event) {
        if (event.valid) {
            tvStatus.setText("完成第 " + event.goodReps + " " + exerciseType.getUnitLabel());
        } else {
            tvStatus.setText("这次不标准：" + (event.reason == null ? "动作不到位" : event.reason));
        }
    }

    @Override
    public void onHoldStart(int attempt) {
        tvStatus.setText("第 " + attempt + " 组开始计时");
    }

    @Override
    public void onHoldEnd(int attempt, long durationMs) {
        tvStatus.setText("第 " + attempt + " 组坚持 " + TimeUtils.formatDurationCn(durationMs)
                + "，可以开始下一组");
    }
}
