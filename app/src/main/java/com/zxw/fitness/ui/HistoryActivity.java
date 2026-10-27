package com.zxw.fitness.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zxw.R;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;
import com.zxw.fitness.util.TimeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 历史记录：按动作筛选、查看缩略图、删除、进入回放。
 */
public class HistoryActivity extends Activity {

    private final List<Session> sessions = new ArrayList<>();
    private SessionAdapter adapter;
    private LinearLayout chipGroup;
    private TextView tvSubtitle;
    private View emptyBox;
    private UserProfile user;

    /** null 表示"全部"。 */
    private ExerciseType filter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        user = UserStore.getCurrent(this);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        chipGroup = findViewById(R.id.chipGroup);
        emptyBox = findViewById(R.id.emptyBox);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        RecyclerView recyclerView = findViewById(R.id.rvSessions);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SessionAdapter();
        recyclerView.setAdapter(adapter);

        buildChips();
    }

    @Override
    protected void onResume() {
        super.onResume();
        user = UserStore.getCurrent(this);
        reload();
    }

    private void buildChips() {
        chipGroup.removeAllViews();
        addChip("全部", null);
        for (ExerciseType type : ExerciseType.values()) {
            addChip(type.getTitle(), type);
        }
    }

    private void addChip(String label, final ExerciseType type) {
        TextView chip = new TextView(this);
        chip.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        chip.setLayoutParams(params);
        chip.setBackgroundResource(R.drawable.fit_bg_chip);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setTextSize(13);
        chip.setTextColor(getResources().getColor(R.color.fit_text_dim));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setTag(type);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = type;
                updateChipStyles();
                reload();
            }
        });
        chipGroup.addView(chip);
        updateChipStyles();
    }

    private void updateChipStyles() {
        for (int i = 0; i < chipGroup.getChildCount(); i++) {
            TextView chip = (TextView) chipGroup.getChildAt(i);
            Object tag = chip.getTag();
            boolean selected = (filter == null && tag == null) || filter == tag;
            chip.setBackgroundResource(selected
                    ? R.drawable.fit_bg_chip_selected : R.drawable.fit_bg_chip);
            chip.setTextColor(getResources().getColor(selected
                    ? R.color.fit_accent : R.color.fit_text_dim));
        }
    }

    private void reload() {
        List<Session> all = filter == null
                ? SessionStore.loadAll(this, user.getId())
                : SessionStore.loadByType(this, user.getId(), filter);
        sessions.clear();
        sessions.addAll(all);
        adapter.notifyDataSetChanged();
        emptyBox.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);

        int total = SessionStore.loadAll(this, user.getId()).size();
        tvSubtitle.setText(String.format(Locale.CHINA, "%s · %s 共 %d 条（全部 %d 条）",
                user.getAvatar(), user.getName(),
                sessions.size(), total));
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value);
    }

    private void openReplay(Session session) {
        Intent intent = new Intent(this, ReplayActivity.class);
        intent.putExtra(ReplayActivity.EXTRA_SESSION_ID, session.getId());
        intent.putExtra(ReplayActivity.EXTRA_USER_ID, session.getUserId());
        startActivity(intent);
    }

    private void confirmDelete(final Session session) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("删除这条记录？")
                .setMessage("同时会删除对应的视频与骨架回放数据，且无法恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SessionStore.delete(HistoryActivity.this, session);
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static String iconFor(ExerciseType type) {
        switch (type) {
            case HANDSTAND:
                return "🤸";
            case WALL_HANDSTAND:
                return "🧗";
            case CHAIR_PUSHUP:
                return "🪑";
            case BAD_PUSHUP:
                return "⚠️";
            case PLANK:
                return "🧘";
            case SQUAT:
                return "🏋";
            case PULLUP:
                return "🧲";
            case WALL_SIT:
                return "🧱";
            case RUNNING:
                return "🏃";
            case PUSHUP:
            default:
                return "💪";
        }
    }

    private Bitmap loadThumb(Session session) {
        File file = SessionStore.resolveMedia(this, session.getUserId(), session.getThumbFile());
        if (file == null || !file.exists()) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 4;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_session, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final Session session = sessions.get(position);
            ExerciseType type = session.getExerciseType();

            Bitmap thumb = loadThumb(session);
            if (thumb != null) {
                holder.ivThumb.setImageBitmap(thumb);
                holder.tvThumbIcon.setVisibility(View.GONE);
            } else {
                holder.ivThumb.setImageDrawable(null);
                holder.tvThumbIcon.setVisibility(View.VISIBLE);
                holder.tvThumbIcon.setText(iconFor(type));
            }

            holder.tvTitle.setText(type.getTitle()
                    + (type == ExerciseType.CHAIR_PUSHUP
                    ? ("hands".equals(session.getVariant()) ? " · 手撑凳" : " · 脚垫凳") : ""));
            holder.tvTime.setText(TimeUtils.formatFriendly(session.getStartedAt())
                    + (session.hasPersonalCalibration() ? " · 个人目标" : ""));

            if (type == ExerciseType.WALL_SIT) {
                holder.tvResult.setText(session.hasTotalHoldMs()
                        ? String.format(Locale.US, "累计 %.1f 秒\n最长 %.1f 秒",
                        session.getTotalHoldMs() / 1000.0, session.getHoldMs() / 1000.0)
                        : "最长 " + session.getResultText());
            } else {
                holder.tvResult.setText(session.getResultText());
            }

            boolean hasVideo = false;
            if (session.hasVideoReplay()) {
                File video = new File(SessionStore.mediaDir(HistoryActivity.this,
                        session.getUserId()), session.getVideoFile());
                hasVideo = video.exists() && video.length() > 0;
            }
            holder.tvBadgeVideo.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
            holder.tvBadgePose.setVisibility(session.hasPoseReplay() ? View.VISIBLE : View.GONE);
            holder.tvPlay.setVisibility(session.hasPoseReplay() || hasVideo
                    ? View.VISIBLE : View.INVISIBLE);
            if (!type.isTimerExercise() && session.getBadReps() > 0) {
                holder.tvBadgeForm.setVisibility(View.VISIBLE);
                holder.tvBadgeForm.setText("不标准 " + session.getBadReps());
            } else if (type.isTimerExercise() && !Float.isNaN(session.getStability())) {
                holder.tvBadgeForm.setVisibility(View.VISIBLE);
                holder.tvBadgeForm.setText("稳定度 " + Math.round(session.getStability() * 100) + "%");
            } else {
                holder.tvBadgeForm.setVisibility(View.GONE);
            }

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openReplay(session);
                }
            });
            holder.btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDelete(session);
                }
            });
        }

        @Override
        public int getItemCount() {
            return sessions.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final TextView tvThumbIcon;
            final TextView tvPlay;
            final TextView tvTitle;
            final TextView tvTime;
            final TextView tvBadgeVideo;
            final TextView tvBadgePose;
            final TextView tvBadgeForm;
            final TextView tvResult;
            final TextView btnDelete;

            Holder(View itemView) {
                super(itemView);
                ivThumb = itemView.findViewById(R.id.ivThumb);
                tvThumbIcon = itemView.findViewById(R.id.tvThumbIcon);
                tvPlay = itemView.findViewById(R.id.tvPlay);
                tvTitle = itemView.findViewById(R.id.tvTitle);
                tvTime = itemView.findViewById(R.id.tvTime);
                tvBadgeVideo = itemView.findViewById(R.id.tvBadgeVideo);
                tvBadgePose = itemView.findViewById(R.id.tvBadgePose);
                tvBadgeForm = itemView.findViewById(R.id.tvBadgeForm);
                tvResult = itemView.findViewById(R.id.tvResult);
                btnDelete = itemView.findViewById(R.id.btnDelete);
            }
        }
    }
}
