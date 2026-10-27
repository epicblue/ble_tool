package com.zxw.fitness.ui;

import android.app.Activity;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.zxw.R;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.camera.SkeletonOverlay;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.util.TimeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 回放页：同时支持"视频回放（叠加骨架）"和"纯骨架动画回放"。
 *
 * <p>骨架数据与视频的播放进度同步；拖动进度条、倍速播放都会重新计算当时的计数/计时，
 * 与录制时的判定完全一致。</p>
 */
public class ReplayActivity extends Activity {

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_USER_ID = "user_id";

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_replay);
        bindViews();

        long sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1);
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

        poseLog = PoseLog.load(SessionStore.resolveMedia(this, session.getUserId(),
                session.getPoseFile()));
        timeline = poseLog != null && !poseLog.isEmpty()
                ? ReplayTimeline.build(poseLog.getFrames(), type, session.getVariant())
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
        tvSubtitle.setText(TimeUtils.formatFriendly(session.getStartedAt())
                + " · 成绩 " + resultText(session) + stability);
        tvUnit.setText(type.getUnitLabel());

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

    private String resultText(Session s) {
        return s.getResultText();
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
                PoseFrame frame = poseLog.getFrames().get(index);
                overlay.setFrame(frame);
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
            // 没有骨架数据时，只展示本次最终成绩
            tvScore.setText(type.isTimerExercise()
                    ? String.format(Locale.US, "%.1f", session.getHoldMs() / 1000.0)
                    : String.valueOf(type == ExerciseType.BAD_PUSHUP
                    ? session.getBadReps() : session.getReps()));
            tvStatus.setText("没有骨架数据，仅显示最终成绩");
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
