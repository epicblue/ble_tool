package com.zxw.fitness.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.zxw.R;
import com.zxw.fitness.analysis.HoldScore;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.camera.SkeletonOverlay;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.VideoExporter;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.pose.PoseSceneDiagnostics;
import com.zxw.fitness.pose.PoseSceneResult;
import com.zxw.fitness.util.TimeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 回放页：同时支持"视频回放（叠加骨架）"和"纯骨架动画回放"。
 *
 * <p>骨架数据与视频的播放进度同步；拖动进度条、倍速播放都会重新计算当时的计数/计时，
 * 按当前版本判定；算法升级后旧记录的结果可能不同。</p>
 */
public class ReplayActivity extends Activity implements VideoExporter.Listener {

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_USER_ID = "user_id";

    private static final int REQ_EXPORT_VIDEO = 202;
    private static final String STATE_SESSION_ID = "replay_session_id";
    private static final String STATE_EXPORT_USER = "export_user";
    private static final String STATE_EXPORT_SESSION = "export_session";
    private static final String STATE_EXPORT_FILE = "export_video_file";
    private static final float[] SPEEDS = {1f, 0.5f, 1.5f, 2f};
    private static final long TICK_MS = 50;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Session> playlist = new ArrayList<>();

    private PoseVideoView videoView;
    private SkeletonOverlay overlay;
    private SeekBar seekBar;
    private TextView btnPlay;
    private TextView btnSpeed;
    private TextView tvTime;
    private TextView tvScore;
    private TextView tvUnit;
    private TextView tvStatus;
    private TextView tvTitle;
    private TextView tvSubtitle;
    private TextView btnPrev;
    private TextView btnNext;
    private TextView tvNoVideo;
    private TextView btnMarkerDetails;
    private TextView btnExportVideo;
    private TextView tvSceneDiagnosis;
    private TextView tvRecordedSummary;

    private Session session;
    private PoseLog poseLog;
    private ReplayTimeline timeline;
    private int playlistIndex;

    private boolean hasVideo;
    private boolean videoReady;
    private boolean playing = true;
    private boolean userSeeking;
    private long durationMs;
    private long clockMs;
    private long lastTickMs;
    private float speed = 1f;
    private int speedIndex;
    private boolean showMarkerDetails = true;
    private int lastSceneIndex = -1;
    // 文件选择器可能使 Activity 重建；固定记录身份与文件名，不能取返回时正在显示的记录。
    private String pendingExportUser;
    private long pendingExportSessionId = -1;
    private String pendingExportFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_replay);
        bindViews();

        long sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1);
        if (savedInstanceState != null) {
            sessionId = savedInstanceState.getLong(STATE_SESSION_ID, sessionId);
            pendingExportUser = savedInstanceState.getString(STATE_EXPORT_USER);
            pendingExportSessionId = savedInstanceState.getLong(STATE_EXPORT_SESSION, -1);
            pendingExportFile = savedInstanceState.getString(STATE_EXPORT_FILE);
        }
        String userId = getIntent().getStringExtra(EXTRA_USER_ID);
        if (userId == null) {
            Toast.makeText(this, "记录不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        List<Session> all = SessionStore.loadAll(this, userId);
        Session target = null;
        for (Session s : all) {
            if (s.getId() == sessionId) {
                target = s;
                break;
            }
        }
        if (target == null) {
            Toast.makeText(this, "记录已被删除", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // 同动作类型的记录组成播放列表，方便连续回看
        for (Session s : all) {
            if (s.getExerciseType() == target.getExerciseType()) {
                playlist.add(s);
            }
        }
        playlistIndex = playlist.indexOf(target);
        if (playlistIndex < 0) {
            playlist.add(target);
            playlistIndex = playlist.size() - 1;
        }
        loadSession();
    }

    private void bindViews() {
        videoView = findViewById(R.id.videoView);
        overlay = findViewById(R.id.overlay);
        seekBar = findViewById(R.id.seekBar);
        btnPlay = findViewById(R.id.btnPlay);
        btnSpeed = findViewById(R.id.btnSpeed);
        tvTime = findViewById(R.id.tvTime);
        tvScore = findViewById(R.id.tvScore);
        tvUnit = findViewById(R.id.tvUnit);
        tvStatus = findViewById(R.id.tvStatus);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        btnPrev = findViewById(R.id.btnPrev);
        btnNext = findViewById(R.id.btnNext);
        tvNoVideo = findViewById(R.id.tvNoVideo);
        btnMarkerDetails = findViewById(R.id.btnMarkerDetails);
        btnExportVideo = findViewById(R.id.btnExportVideo);
        tvSceneDiagnosis = findViewById(R.id.tvSceneDiagnosis);
        tvRecordedSummary = findViewById(R.id.tvRecordedSummary);
        btnExportVideo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmExportVideo(); }
        });
        btnMarkerDetails.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showMarkerDetails = !showMarkerDetails;
                btnMarkerDetails.setText(showMarkerDetails ? "标记：开" : "标记：关");
                overlay.setShowDetails(showMarkerDetails);
                lastSceneIndex = -1;
                if (session != null) render(hasVideo && videoReady ? videoView.getCurrentPosition() : clockMs);
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                speedIndex = (speedIndex + 1) % SPEEDS.length;
                speed = SPEEDS[speedIndex];
                btnSpeed.setText(String.format(Locale.US, "%.1fx", speed));
                applyVideoSpeed();
            }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playlistIndex > 0) {
                    playlistIndex--;
                    loadSession();
                }
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (playlistIndex < playlist.size() - 1) {
                    playlistIndex++;
                    loadSession();
                }
            }
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                long target = durationMs * progress / 1000;
                seekTo(target);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
            }
        });
    }

    private void loadSession() {
        handler.removeCallbacksAndMessages(null);
        session = playlist.get(playlistIndex);
        ExerciseType type = session.getExerciseType();

        videoView.stopPlayback();
        videoReady = false;
        hasVideo = false;
        playing = true;
        clockMs = 0;
        lastTickMs = 0;
        lastSceneIndex = -1;
        overlay.setPendingJumps(null);
        overlay.setFrame(null);
        tvSceneDiagnosis.setText("没有可用的骨架记录");

        poseLog = PoseLog.load(SessionStore.resolveMedia(this, session.getUserId(),
                session.getPoseFile()));
        overlay.setPixelCoordinates(poseLog != null && poseLog.isLegacyPixelCoordinates());
        timeline = poseLog != null && !poseLog.isEmpty()
                ? ReplayTimeline.build(poseLog.getFrames(), session)
                : null;

        File video = SessionStore.resolveMedia(this, session.getUserId(), session.getVideoFile());
        hasVideo = video != null && video.exists() && video.length() > 0;
        durationMs = Math.max(session.getDurationMs(),
                poseLog != null ? poseLog.getDurationMs() : 0);

        tvTitle.setText(type.getTitle()
                + (type == ExerciseType.CHAIR_PUSHUP
                ? ("hands".equals(session.getVariant()) ? " · 手撑凳" : " · 脚垫凳") : ""));
        String stability = Float.isNaN(session.getStability())
                ? "" : " · 稳定度 " + Math.round(session.getStability() * 100) + "%";
        String scoreLabel = type == ExerciseType.WALL_SIT ? " · 最长 " : " · 成绩 ";
        tvSubtitle.setText(TimeUtils.formatFriendly(session.getStartedAt())
                + scoreLabel + session.getResultText() + stability
                + (session.hasPersonalCalibration() ? " · 个人目标" : ""));
        if (type == ExerciseType.WALL_SIT) {
            boolean canShowTotal = timeline != null || session.hasTotalHoldMs();
            tvUnit.setText(canShowTotal ? HoldScore.unitLabel(type) : "秒（最长）");
            tvRecordedSummary.setVisibility(View.VISIBLE);
            tvRecordedSummary.setText("保存：" + session.getResultSummary()
                    + (timeline != null ? " · 回放按当前算法重算" : ""));
        } else {
            tvUnit.setText(type.getUnitLabel());
            tvRecordedSummary.setVisibility(View.GONE);
        }
        updateExportButton();

        btnPrev.setVisibility(playlistIndex > 0 ? View.VISIBLE : View.GONE);
        btnNext.setVisibility(playlistIndex < playlist.size() - 1 ? View.VISIBLE : View.GONE);

        if (hasVideo) {
            videoView.setVisibility(View.VISIBLE);
            tvNoVideo.setVisibility(View.GONE);
            videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    videoReady = true;
                    durationMs = Math.max(durationMs, mp.getDuration());
                    int vw = mp.getVideoWidth();
                    int vh = mp.getVideoHeight();
                    // 竖屏拍摄的 mp4 由播放器按旋转信息渲染，使用骨架记录的画面比例更可靠
                    if (poseLog != null) {
                        vw = poseLog.getViewWidth();
                        vh = poseLog.getViewHeight();
                    } else if (vw > vh) {
                        int temp = vw;
                        vw = vh;
                        vh = temp;
                    }
                    videoView.setVideoAspect(vw, vh);
                    overlay.setSourceSize(vw, vh);
                    applyVideoSpeed();
                    videoView.seekTo(1);
                    updatePlayButton();
                    if (playing) {
                        videoView.start();
                    }
                    render(0);
                }
            });
            videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Toast.makeText(ReplayActivity.this, "视频无法播放，改为骨架回放",
                            Toast.LENGTH_SHORT).show();
                    videoView.setVisibility(View.GONE);
                    tvNoVideo.setVisibility(View.VISIBLE);
                    hasVideo = false;
                    videoReady = false;
                    if (poseLog != null) {
                        overlay.setSourceSize(poseLog.getViewWidth(), poseLog.getViewHeight());
                    }
                    return true;
                }
            });
            videoView.setVideoPath(video.getAbsolutePath());
            videoView.requestFocus();
        } else {
            videoView.setVisibility(View.GONE);
            tvNoVideo.setVisibility(View.VISIBLE);
            tvNoVideo.setText(poseLog != null
                    ? "本记录没有视频，正在回放骨架动作"
                    : "本记录没有可回放的画面");
            if (poseLog != null) {
                overlay.setSourceSize(poseLog.getViewWidth(), poseLog.getViewHeight());
            }
        }

        seekBar.setMax(1000);
        seekBar.setProgress(0);
        updatePlayButton();
        render(0);
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long time;
            if (hasVideo && videoReady) {
                if (playing) {
                    time = videoView.getCurrentPosition();
                    if (durationMs > 0 && time >= durationMs - 30) {
                        playing = false;
                        videoView.pause();
                        updatePlayButton();
                    }
                } else {
                    time = clockMs;
                }
            } else {
                long now = SystemClock.elapsedRealtime();
                if (playing) {
                    if (lastTickMs > 0) {
                        clockMs += (long) ((now - lastTickMs) * speed);
                    }
                    if (durationMs > 0 && clockMs >= durationMs) {
                        clockMs = durationMs;
                        playing = false;
                        updatePlayButton();
                    }
                }
                lastTickMs = now;
                time = clockMs;
            }
            if (playing || !hasVideo) {
                render(time);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private void render(long timeMs) {
        if (!userSeeking && durationMs > 0) {
            seekBar.setProgress((int) Math.min(1000, timeMs * 1000 / durationMs));
        }
        tvTime.setText(TimeUtils.formatStopwatch(timeMs) + " / "
                + TimeUtils.formatStopwatch(durationMs));

        ExerciseType type = session == null ? ExerciseType.PUSHUP : session.getExerciseType();
        if (timeline != null && timeline.size() > 0) {
            int index = timeline.indexAt(timeMs);
            if (index >= 0 && poseLog != null && index < poseLog.getFrames().size()) {
                PoseFrame frame = poseLog.getDisplayFrames().get(index);
                overlay.setFrame(frame);
                if (index != lastSceneIndex) {
                    PoseSceneResult result = PoseSceneDiagnostics.inspect(frame, type,
                            poseLog.getViewWidth(), poseLog.getViewHeight(), poseLog.isLegacyPixelCoordinates());
                    String legend = showMarkerDetails ? "绿≥50% 黄30–49%；无点=缺失/出画"
                            : "详细标记已关闭；基本骨架仍显示";
                    tvSceneDiagnosis.setText("回放滤波 " + result.reliablePoints + "/" + result.totalPoints
                            + " · " + result.message + "\n" + legend);
                    int color = result.quality == PoseSceneResult.Quality.USABLE
                            ? R.color.fit_text : R.color.fit_warn;
                    tvSceneDiagnosis.setTextColor(ContextCompat.getColor(this, color));
                    lastSceneIndex = index;
                }
            }
            int good = timeline.getGoodRepsAt(index);
            int bad = timeline.getBadRepsAt(index);
            if (type.isTimerExercise()) {
                tvScore.setText(String.format(Locale.US, "%.1f",
                        timeline.getHoldMsAt(index) / 1000.0));
            } else {
                tvScore.setText(String.valueOf(type == ExerciseType.BAD_PUSHUP ? bad : good));
            }
            String counts;
            if (type.isTimerExercise()) {
                counts = "";
            } else if (type == ExerciseType.RUNNING) {
                counts = String.format(Locale.CHINA, " · 共 %d 步", good);
            } else {
                counts = String.format(Locale.CHINA, " · 有效 %d · 不标准 %d", good, bad);
            }
            tvStatus.setText(timeline.getStatusAt(index) + counts);
        } else {
            // 旧记录没有累计字段；无骨架时只能显示已保存的最长单组。
            long savedHold = type == ExerciseType.WALL_SIT && session.hasTotalHoldMs()
                    ? session.getTotalHoldMs() : session.getHoldMs();
            tvScore.setText(type.isTimerExercise()
                    ? String.format(Locale.US, "%.1f", savedHold / 1000.0)
                    : String.valueOf(type == ExerciseType.BAD_PUSHUP
                    ? session.getBadReps() : session.getReps()));
            tvStatus.setText(type == ExerciseType.WALL_SIT
                    ? (session.hasTotalHoldMs() ? "无骨架，显示保存的累计" : "旧记录未保存累计，仅显示最长单组")
                    : "没有骨架数据，仅显示最终成绩");
        }
    }

    private void seekTo(long targetMs) {
        if (durationMs <= 0) {
            return;
        }
        clockMs = Math.max(0, Math.min(durationMs, targetMs));
        if (hasVideo && videoReady) {
            videoView.seekTo((int) clockMs);
        }
        lastTickMs = SystemClock.elapsedRealtime();
        render(clockMs);
    }

    private void togglePlay() {
        playing = !playing;
        if (hasVideo && videoReady) {
            if (playing) {
                if (durationMs > 0 && videoView.getCurrentPosition() >= durationMs - 50) {
                    videoView.seekTo(0);
                    clockMs = 0;
                }
                videoView.start();
                applyVideoSpeed();
            } else {
                clockMs = videoView.getCurrentPosition();
                videoView.pause();
            }
        } else {
            if (playing && durationMs > 0 && clockMs >= durationMs) {
                clockMs = 0;
            }
            lastTickMs = SystemClock.elapsedRealtime();
        }
        updatePlayButton();
    }

    private void applyVideoSpeed() {
        if (hasVideo && videoReady) {
            // 真正的倍速由 PoseVideoView 内部的 MediaPlayer.setPlaybackParams 实现
            videoView.setSpeed(speed);
        }
    }

    private void updatePlayButton() {
        btnPlay.setText(playing ? "⏸  暂停" : "▶  播放");
    }

    private void updateExportButton() {
        if (btnExportVideo == null) return;
        File source = session == null ? null : SessionStore.resolveMedia(this,
                session.getUserId(), session.getVideoFile());
        boolean available = source != null && source.isFile() && source.length() > 0;
        boolean busy = VideoExporter.isRunning();
        btnExportVideo.setText(busy ? "导出中" : available ? "导出视频" : "无视频");
        btnExportVideo.setEnabled(available && !busy);
        btnExportVideo.setAlpha(available && !busy ? 1f : 0.55f);
    }

    private void confirmExportVideo() {
        final Session chosen = session;
        if (chosen == null) return;
        if (VideoExporter.isRunning()) {
            updateExportButton();
            Toast.makeText(this, "已有视频正在导出", Toast.LENGTH_SHORT).show();
            return;
        }
        File source = SessionStore.resolveMedia(this, chosen.getUserId(), chosen.getVideoFile());
        if (source == null || !source.isFile() || source.length() <= 0) {
            updateExportButton();
            Toast.makeText(this, "本记录没有可导出的视频", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("导出原视频")
                .setMessage("保存摄像头录制的原始 MP4。骨架、关键点标记和成绩是回放时叠加的，不会包含在导出文件中。\n\n请选择保存位置，并等待导出完成；原记录不会被删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择位置", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        launchVideoDestination(chosen);
                    }
                }).show();
    }

    private void launchVideoDestination(Session chosen) {
        if (VideoExporter.isRunning()) {
            Toast.makeText(this, "已有视频正在导出", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExportUser = chosen.getUserId();
        pendingExportSessionId = chosen.getId();
        pendingExportFile = chosen.getVideoFile();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/mp4");
        intent.putExtra(Intent.EXTRA_TITLE, "fitness_" + chosen.getExerciseType().getKey()
                + "_" + chosen.getId() + ".mp4");
        try {
            startActivityForResult(intent, REQ_EXPORT_VIDEO);
        } catch (ActivityNotFoundException | SecurityException e) {
            clearPendingExport();
            Toast.makeText(this, "无法打开系统文件选择器，请启用文件管理器后重试", Toast.LENGTH_LONG).show();
        }
    }

    private void clearPendingExport() {
        pendingExportUser = null;
        pendingExportSessionId = -1;
        pendingExportFile = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT_VIDEO) return;
        String userId = pendingExportUser;
        long sessionId = pendingExportSessionId;
        String videoName = pendingExportFile;
        clearPendingExport();
        if (resultCode != RESULT_OK) {
            Toast.makeText(this, "已取消导出", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri destination = data == null ? null : data.getData();
        if (destination == null || !"content".equals(destination.getScheme())) {
            Toast.makeText(this, "未获取到有效的保存位置，请重试", Toast.LENGTH_LONG).show();
            return;
        }
        Session selected = null;
        if (userId != null && sessionId >= 0 && videoName != null) {
            for (Session item : SessionStore.loadAll(this, userId)) {
                if (item.getId() == sessionId && userId.equals(item.getUserId())
                        && videoName.equals(item.getVideoFile())) {
                    selected = item;
                    break;
                }
            }
        }
        File source = selected == null ? null : SessionStore.resolveMedia(this, userId, videoName);
        if (source == null || !source.isFile() || source.length() <= 0) {
            Toast.makeText(this, "原记录或视频已不存在；请检查所选位置是否留下空文件",
                    Toast.LENGTH_LONG).show();
            updateExportButton();
            return;
        }
        if (VideoExporter.start(getApplicationContext(), source, destination)) {
            updateExportButton();
            Toast.makeText(this, "正在导出，请等待完成提示", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "无法启动导出；所选位置可能留下空文件，请检查后重试",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onVideoExportFinished(boolean success, String message) {
        updateExportButton();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (session != null) state.putLong(STATE_SESSION_ID, session.getId());
        if (pendingExportUser != null) {
            state.putString(STATE_EXPORT_USER, pendingExportUser);
            state.putLong(STATE_EXPORT_SESSION, pendingExportSessionId);
            state.putString(STATE_EXPORT_FILE, pendingExportFile);
        }
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onStart() {
        super.onStart();
        VideoExporter.attach(this);
        updateExportButton();
    }

    @Override
    protected void onStop() {
        VideoExporter.detach(this);
        super.onStop();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (playing) {
            playing = false;
            if (hasVideo && videoReady) {
                clockMs = videoView.getCurrentPosition();
                videoView.pause();
            }
            updatePlayButton();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}
