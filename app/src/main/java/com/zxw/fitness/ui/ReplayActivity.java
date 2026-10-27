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
import com.zxw.fitness.analysis.AlgorithmVersion;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.VideoPoseReanalysis;
import com.zxw.fitness.camera.SkeletonOverlay;
import com.zxw.fitness.camera.VideoPoseReanalyzer;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.RecordedTimeline;
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
 * <p>同版本且缓存完整时直接播放录制的逐帧结果；旧版或不完整时回退至重算。
 * 可主动重新识别视频，重识别结果仅驻留内存，不改写历史成绩。</p>
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
    private static final int MODE_RECORDED = 0;
    private static final int MODE_NEW_RAW = 1;
    private static final int MODE_NEW_FILTERED = 2;

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
    private TextView btnPoseMode;
    private TextView tvReplayInfo;
    private TextView btnExportVideo;
    private TextView tvSceneDiagnosis;
    private TextView tvRecordedSummary;

    private Session session;
    private PoseLog poseLog;
    private ReplayTimeline timeline;
    private ReplayTimeline recordedTimeline;
    private boolean reusedRecordedResults;
    private VideoPoseReanalyzer videoReanalyzer;
    private VideoPoseReanalysis videoAnalysis;
    private File currentVideo;
    private int poseMode = MODE_RECORDED;
    private boolean analyzingVideo;
    private boolean lifecyclePaused;
    private String fallbackReason = "";
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
        videoReanalyzer = new VideoPoseReanalyzer();

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
            if (ExerciseType.sameCatalog(s.getExerciseKey(), target.getExerciseKey())
                    || (target.getExerciseKey() != null && ExerciseType.findByKey(target.getExerciseKey()) == null
                    && target.getExerciseKey().equals(s.getExerciseKey()))) {
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
        btnPoseMode = findViewById(R.id.btnPoseMode);
        tvReplayInfo = findViewById(R.id.tvReplayInfo);
        btnExportVideo = findViewById(R.id.btnExportVideo);
        tvSceneDiagnosis = findViewById(R.id.tvSceneDiagnosis);
        tvRecordedSummary = findViewById(R.id.tvRecordedSummary);
        btnExportVideo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmExportVideo(); }
        });
        btnPoseMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPoseModeClicked(); }
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
        videoReanalyzer.cancel();
        session = playlist.get(playlistIndex);
        ExerciseType type = session.getExerciseType();

        videoView.stopPlayback();
        videoReady = false;
        hasVideo = false;
        analyzingVideo = false;
        videoAnalysis = null;
        fallbackReason = "";
        poseMode = MODE_RECORDED;
        playing = true;
        userSeeking = false;
        clockMs = 0;
        lastTickMs = 0;
        lastSceneIndex = -1;
        overlay.setMirrored(false);
        overlay.setPendingJumps(null);
        overlay.setFrame(null);
        tvSceneDiagnosis.setText("没有可用的骨架记录");

        File poseFile = SessionStore.resolveMedia(this, session.getUserId(), session.getPoseFile());
        poseLog = PoseLog.load(poseFile);
        File cachedFile = SessionStore.resolveMedia(this, session.getUserId(), session.getTimelineFile());
        recordedTimeline = RecordedTimeline.load(cachedFile, poseFile, poseLog, session);
        reusedRecordedResults = recordedTimeline != null;
        if (recordedTimeline == null && poseLog != null && !poseLog.isEmpty()) {
            recordedTimeline = ReplayTimeline.build(poseLog.getFrames(), session);
        }
        timeline = recordedTimeline;
        overlay.setPixelCoordinates(poseLog != null && poseLog.isLegacyPixelCoordinates());

        currentVideo = SessionStore.resolveMedia(this, session.getUserId(), session.getVideoFile());
        hasVideo = currentVideo != null && currentVideo.isFile() && currentVideo.length() > 0;
        durationMs = Math.max(session.getDurationMs(),
                poseLog != null ? poseLog.getDurationMs() : 0);

        tvTitle.setText(type.historyTitle()
                + (type == ExerciseType.CHAIR_PUSHUP
                ? ("hands".equals(session.getVariant()) ? " · 手撑凳" : " · 脚垫凳") : ""));
        String stability = Float.isNaN(session.getStability())
                ? "" : " · 稳定度 " + Math.round(session.getStability() * 100) + "%";
        String scoreLabel = type == ExerciseType.WALL_SIT ? " · 最长 " : " · 成绩 ";
        tvSubtitle.setText(TimeUtils.formatFriendly(session.getStartedAt())
                + scoreLabel + (type == ExerciseType.BAD_PUSHUP ? "不标准 " : "")
                + session.getResultText() + stability
                + (session.hasPersonalCalibration() ? " · 个人目标" : ""));
        updateSummaryLabels();
        updateExportButton();

        btnPrev.setVisibility(playlistIndex > 0 ? View.VISIBLE : View.GONE);
        btnNext.setVisibility(playlistIndex < playlist.size() - 1 ? View.VISIBLE : View.GONE);
        seekBar.setMax(1000);
        seekBar.setProgress(0);
        setPlaybackControlsEnabled(true);

        if (hasVideo) {
            videoView.setVisibility(View.VISIBLE);
            tvNoVideo.setVisibility(View.GONE);
            videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer mp) {
                    videoReady = true;
                    // 叠加索引以视频本身的时间为准，不能以更长的训练计时作为视频时长。
                    if (mp.getDuration() > 0) durationMs = mp.getDuration();
                    int vw = mp.getVideoWidth();
                    int vh = mp.getVideoHeight();
                    if (videoAnalysis != null) {
                        vw = videoAnalysis.getSourceWidth();
                        vh = videoAnalysis.getSourceHeight();
                    } else if (poseLog != null) {
                        vw = poseLog.getViewWidth();
                        vh = poseLog.getViewHeight();
                    } else if (vw > vh) {
                        int temp = vw;
                        vw = vh;
                        vh = temp;
                    }
                    videoView.setVideoAspect(vw, vh);
                    if (poseMode != MODE_RECORDED || poseLog == null) overlay.setSourceSize(vw, vh);
                    else overlay.setSourceSize(poseLog.getViewWidth(), poseLog.getViewHeight());
                    applyVideoSpeed();
                    videoView.seekTo((int) Math.min(Integer.MAX_VALUE, clockMs));
                    updatePlayButton();
                    if (playing && !analyzingVideo) videoView.start();
                    render(clockMs);
                }
            });
            videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                    Toast.makeText(ReplayActivity.this, "视频无法播放，改为骨架回放",
                            Toast.LENGTH_SHORT).show();
                    if (analyzingVideo) videoReanalyzer.cancel();
                    analyzingVideo = false;
                    videoAnalysis = null;
                    hasVideo = false;
                    videoReady = false;
                    durationMs = Math.max(session.getDurationMs(),
                            poseLog != null ? poseLog.getDurationMs() : 0);
                    videoView.setVisibility(View.GONE);
                    tvNoVideo.setVisibility(View.VISIBLE);
                    tvNoVideo.setText(poseLog != null ? "视频无法播放，正在回放录制骨架"
                            : "视频无法播放，且没有录制骨架");
                    fallbackReason = "视频无法播放";
                    playing = !lifecyclePaused;
                    lastTickMs = SystemClock.elapsedRealtime();
                    setPlaybackControlsEnabled(true);
                    selectPoseMode(MODE_RECORDED);
                    updatePlayButton();
                    render(clockMs);
                    return true;
                }
            });
            if (!reusedRecordedResults) beginVideoReanalysis();
            else updatePoseControls();
            videoView.setVideoPath(currentVideo.getAbsolutePath());
            videoView.requestFocus();
        } else {
            videoView.setVisibility(View.GONE);
            tvNoVideo.setVisibility(View.VISIBLE);
            tvNoVideo.setText(poseLog != null
                    ? "本记录没有视频，正在回放骨架动作"
                    : "本记录没有可回放的画面");
            if (poseLog != null) overlay.setSourceSize(poseLog.getViewWidth(), poseLog.getViewHeight());
            updatePoseControls();
        }

        updatePlayButton();
        render(0);
        handler.post(tick);
    }

    /** 旧记录/缓存缺失自动重识别；同版本缓存可用时仅在用户主动点击后运行。 */
    private void beginVideoReanalysis() {
        if (!hasVideo || currentVideo == null || analyzingVideo) return;
        if (videoReady) {
            clockMs = videoView.getCurrentPosition();
            videoView.pause();
        }
        playing = false;
        analyzingVideo = true;
        fallbackReason = "";
        videoAnalysis = null;
        selectPoseMode(MODE_RECORDED);
        setPlaybackControlsEnabled(false);
        updatePlayButton();
        tvReplayInfo.setText("正在从原视频重新识别 0% · 耗时取决于设备；可点“取消识别”返回普通回放");
        videoReanalyzer.start(currentVideo, session, new VideoPoseReanalyzer.Listener() {
            @Override public void onProgress(int complete, int total) {
                int percent = total > 0 ? complete * 100 / total : 0;
                tvReplayInfo.setText("正在从原视频重新识别 " + percent + "%（" + complete + "/" + total
                        + "）· 可点“取消识别”返回普通回放");
            }

            @Override public void onCompleted(VideoPoseReanalysis result) {
                analyzingVideo = false;
                videoAnalysis = result;
                if (!videoReady) durationMs = result.getDurationMs();
                clockMs = 0;
                // 默认展示经过当前 PoseSmoother 的结果，便于直接检验新滤波/重捕获算法。
                selectPoseMode(MODE_NEW_FILTERED);
                setPlaybackControlsEnabled(true);
                if (videoReady) videoView.seekTo(0);
                playing = !lifecyclePaused;
                if (playing && videoReady) {
                    videoView.start();
                    applyVideoSpeed();
                }
                lastTickMs = SystemClock.elapsedRealtime();
                updatePlayButton();
                render(0);
            }

            @Override public void onError(String message) {
                stopVideoReanalysis("重识别失败：" + message, true);
                Toast.makeText(ReplayActivity.this, "重识别失败：" + message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void stopVideoReanalysis(String reason, boolean resumePlayback) {
        videoReanalyzer.cancel();
        analyzingVideo = false;
        videoAnalysis = null;
        fallbackReason = reason;
        setPlaybackControlsEnabled(true);
        selectPoseMode(MODE_RECORDED);
        playing = resumePlayback && !lifecyclePaused;
        if (playing && hasVideo && videoReady) {
            videoView.start();
            applyVideoSpeed();
        }
        lastTickMs = SystemClock.elapsedRealtime();
        updatePlayButton();
        render(clockMs);
    }

    private void onPoseModeClicked() {
        if (analyzingVideo) {
            stopVideoReanalysis("已取消重识别", true);
        } else if (videoAnalysis == null) {
            beginVideoReanalysis();
        } else if (poseMode == MODE_NEW_RAW) {
            selectPoseMode(MODE_NEW_FILTERED);
        } else if (poseMode == MODE_NEW_FILTERED && recordedTimeline != null) {
            selectPoseMode(MODE_RECORDED);
        } else {
            selectPoseMode(MODE_NEW_RAW);
        }
        if (!analyzingVideo) render(hasVideo && videoReady ? videoView.getCurrentPosition() : clockMs);
    }

    private void selectPoseMode(int mode) {
        poseMode = mode;
        lastSceneIndex = -1;
        overlay.setPendingJumps(null);
        if (mode == MODE_RECORDED || videoAnalysis == null) {
            timeline = recordedTimeline;
            overlay.setPixelCoordinates(poseLog != null && poseLog.isLegacyPixelCoordinates());
            if (poseLog != null) overlay.setSourceSize(poseLog.getViewWidth(), poseLog.getViewHeight());
            else overlay.setFrame(null);
        } else {
            timeline = videoAnalysis.getTimeline();
            overlay.setPixelCoordinates(false);
            overlay.setSourceSize(videoAnalysis.getSourceWidth(), videoAnalysis.getSourceHeight());
        }
        // 视频本身的比例始终以解码画面为准，切回旧骨架时不要拉伸视频。
        if (hasVideo && videoAnalysis != null) {
            videoView.setVideoAspect(videoAnalysis.getSourceWidth(), videoAnalysis.getSourceHeight());
        }
        updateSummaryLabels();
        updatePoseControls();
    }

    private void setPlaybackControlsEnabled(boolean enabled) {
        btnPlay.setEnabled(enabled);
        btnSpeed.setEnabled(enabled);
        seekBar.setEnabled(enabled);
        btnPlay.setAlpha(enabled ? 1f : 0.55f);
        btnSpeed.setAlpha(enabled ? 1f : 0.55f);
    }

    private void updateSummaryLabels() {
        if (session == null) return;
        ExerciseType type = session.getExerciseType();
        if (type == ExerciseType.WALL_SIT) {
            boolean canShowTotal = timeline != null || session.hasTotalHoldMs();
            tvUnit.setText(canShowTotal ? HoldScore.unitLabel(type) : "秒（最长）");
        } else {
            tvUnit.setText(type == ExerciseType.BAD_PUSHUP ? "次（旧不标准）" : type.getUnitLabel());
        }
        tvRecordedSummary.setVisibility(View.VISIBLE);
        tvRecordedSummary.setText("历史保存：" + session.getResultSummary()
                + (timeline == null ? "" : poseMode == MODE_RECORDED && reusedRecordedResults
                ? " · 大字为录制结果" : " · 大字为回放重算"));
    }

    private void updatePoseControls() {
        if (analyzingVideo) {
            btnPoseMode.setText("取消识别");
            btnPoseMode.setEnabled(true);
            btnPoseMode.setAlpha(1f);
            return;
        }
        if (!hasVideo) {
            btnPoseMode.setText("仅录制骨架");
            btnPoseMode.setEnabled(false);
            btnPoseMode.setAlpha(0.55f);
            String detail = recordedTimeline == null ? "没有录制骨架，仅显示保存的成绩"
                    : reusedRecordedResults ? "复用录制逐帧结果，未重新计算" : "录制骨架按当前判定器重算";
            tvReplayInfo.setText((fallbackReason.isEmpty() ? "纯骨架回放" : fallbackReason)
                    + " · " + detail + "\n" + versionLabel());
            return;
        }
        btnPoseMode.setEnabled(true);
        btnPoseMode.setAlpha(1f);
        if (videoAnalysis == null) {
            btnPoseMode.setText("重新识别");
            String detail = recordedTimeline == null ? "没有录制骨架"
                    : reusedRecordedResults ? "已复用录制逐帧结果，未重新计算" : "录制骨架已按当前判定器重算";
            tvReplayInfo.setText((fallbackReason.isEmpty() ? detail : fallbackReason + " · " + detail)
                    + " · 可点击“重新识别”比较\n" + versionLabel());
        } else if (poseMode == MODE_RECORDED) {
            btnPoseMode.setText("骨架：录制");
            tvReplayInfo.setText((reusedRecordedResults ? "录制骨架与逐帧结果（未重算）"
                    : "录制骨架按当前判定器重算") + " · 点击切换新识别\n" + versionLabel());
        } else {
            btnPoseMode.setText(poseMode == MODE_NEW_RAW ? "骨架：新原始" : "骨架：新滤波");
            String mode = "连续跟踪";
            String sparse = videoAnalysis.getIntervalMs() > 100 ? " · 较稀采样可能漏动作" : "";
            String missing = videoAnalysis.getMissingFrames() > 0
                    ? " · " + videoAnalysis.getMissingFrames() + " 帧未解码" : "";
            tvReplayInfo.setText("视频新识别（" + mode + "，每 " + videoAnalysis.getIntervalMs()
                    + "ms）" + sparse + missing
                    + " · 仅临时回放；无重力补偿/组中校准，计数供参考\n" + versionLabel());
        }
        btnPoseMode.setContentDescription(btnPoseMode.getText() + "，点击切换或重新识别");
    }

    private String versionLabel() {
        String recorded = session.getAlgorithmVersion();
        return "算法：录制 " + (recorded == null || recorded.isEmpty() ? "旧记录未记录" : recorded)
                + " / 当前 " + AlgorithmVersion.forExercise(session.getExerciseType());
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            boolean wasPlaying = playing;
            long time;
            if (hasVideo) {
                // 尚未准备好播放器时不要用系统时钟假装视频已经播放。
                time = videoReady && playing ? videoView.getCurrentPosition() : clockMs;
                if (videoReady && playing) {
                    clockMs = time;
                    if (durationMs > 0 && time >= durationMs - 30) {
                        playing = false;
                        videoView.pause();
                        updatePlayButton();
                    }
                }
            } else {
                long now = SystemClock.elapsedRealtime();
                if (playing) {
                    if (lastTickMs > 0) clockMs += (long) ((now - lastTickMs) * speed);
                    if (durationMs > 0 && clockMs >= durationMs) {
                        clockMs = durationMs;
                        playing = false;
                        updatePlayButton();
                    }
                }
                lastTickMs = now;
                time = clockMs;
            }
            if (wasPlaying || !hasVideo) render(time);
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
            PoseFrame frame = null;
            int sourceWidth = 0;
            int sourceHeight = 0;
            boolean pixels = false;
            String frameLabel = "录制滤波";
            if (index >= 0 && poseMode == MODE_RECORDED && poseLog != null
                    && index < poseLog.getDisplayFrames().size()) {
                frame = poseLog.getDisplayFrames().get(index);
                sourceWidth = poseLog.getViewWidth();
                sourceHeight = poseLog.getViewHeight();
                pixels = poseLog.isLegacyPixelCoordinates();
                overlay.setPendingJumps(null);
            } else if (index >= 0 && videoAnalysis != null && index < videoAnalysis.size()
                    && poseMode != MODE_RECORDED) {
                boolean raw = poseMode == MODE_NEW_RAW;
                frame = raw ? videoAnalysis.getRawFrame(index) : videoAnalysis.getFilteredFrame(index);
                sourceWidth = videoAnalysis.getSourceWidth();
                sourceHeight = videoAnalysis.getSourceHeight();
                frameLabel = raw ? "新识别原始" : "新识别滤波";
                overlay.setPendingJumps(raw ? videoAnalysis.getPendingJumps(index) : null);
            }
            overlay.setFrame(frame);
            if (frame != null && index != lastSceneIndex) {
                PoseSceneResult result = PoseSceneDiagnostics.inspect(frame, type,
                        sourceWidth, sourceHeight, pixels);
                String legend = showMarkerDetails ? "绿≥50% 黄30–49%；无点=缺失/出画"
                        + (poseMode == MODE_NEW_RAW ? "；紫圈=滤波待定" : "")
                        : "详细标记已关闭；基本骨架仍显示";
                tvSceneDiagnosis.setText(frameLabel + " " + result.reliablePoints + "/" + result.totalPoints
                        + " · " + result.message + "\n" + legend);
                int color = result.quality == PoseSceneResult.Quality.USABLE
                        ? R.color.fit_text : R.color.fit_warn;
                tvSceneDiagnosis.setTextColor(ContextCompat.getColor(this, color));
                lastSceneIndex = index;
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
            overlay.setPendingJumps(null);
            overlay.setFrame(null);
            tvSceneDiagnosis.setText("没有可用的录制骨架");
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
        if (session != null) {
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        lifecyclePaused = false;
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(tick);
        if (analyzingVideo && session != null) {
            stopVideoReanalysis("离开页面已停止重识别", false);
        }
        VideoExporter.detach(this);
        super.onStop();
    }

    @Override
    protected void onPause() {
        lifecyclePaused = true;
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
        if (videoReanalyzer != null) videoReanalyzer.shutdown();
        if (videoView != null) {
            videoView.stopPlayback();
        }
        super.onDestroy();
    }
}
