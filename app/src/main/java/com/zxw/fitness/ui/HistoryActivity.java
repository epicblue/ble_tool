package com.zxw.fitness.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zxw.R;
import com.zxw.fitness.analysis.HistoryGrowth;
import com.zxw.fitness.analysis.HistoryGrowth.Day;
import com.zxw.fitness.analysis.HistoryGrowth.Badge;
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
import java.util.TimeZone;

/** 成长星球：根据所显示账号的真实记录展示星星与徽章，仍支持筛选、删除和回放。 */
public class HistoryActivity extends Activity {
    /** 训练完成后可固定显示本次训练所属账号；普通入口不传则显示当前账号。 */
    public static final String EXTRA_USER_ID = "history_user_id";
    private static final String STATE_FILTER = "history_filter";
    private static final String[] WEEKDAYS = {"", "周日", "周一", "周二", "周三", "周四", "周五", "周六"};
    private static final int[] BADGE_BACKGROUNDS = {
            R.drawable.history_bg_badge_1, R.drawable.history_bg_badge_2,
            R.drawable.history_bg_badge_3
    };

    private final List<Session> allSessions = new ArrayList<>();
    private final List<Session> sessions = new ArrayList<>();
    private SessionAdapter adapter;
    private RecyclerView recyclerView;
    private LinearLayout chipGroup;
    private TextView tvSubtitle;
    private UserProfile user;
    private String requestedUserId;
    private HistoryGrowth growth;

    /** null 表示全部；统计和徽章始终基于所显示账号的全部记录。 */
    private ExerciseType filter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        if (savedInstanceState != null) filter = typeForKey(savedInstanceState.getString(STATE_FILTER));

        requestedUserId = getIntent().getStringExtra(EXTRA_USER_ID);
        tvSubtitle = findViewById(R.id.tvSubtitle);
        chipGroup = findViewById(R.id.chipGroup);
        recyclerView = findViewById(R.id.rvSessions);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SessionAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnSeeRecords).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scrollToRecords(); }
        });
        buildChips();
    }

    @Override
    protected void onResume() {
        super.onResume();
        user = requestedUserId == null || requestedUserId.isEmpty()
                ? UserStore.getCurrent(this) : UserStore.getById(this, requestedUserId);
        if (user == null) {
            Toast.makeText(this, "这个账号已不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        reload();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (filter != null) state.putString(STATE_FILTER, filter.getKey());
        super.onSaveInstanceState(state);
    }

    private static ExerciseType typeForKey(String key) {
        if (key != null) {
            for (ExerciseType type : ExerciseType.values()) {
                if (type.getKey().equals(key)) return type.catalogType();
            }
        }
        return null;
    }

    private void buildChips() {
        chipGroup.removeAllViews();
        addChip("全部", null);
        for (ExerciseType type : ExerciseType.selectableValues()) addChip(type.getTitle(), type);
        updateChipStyles();
    }

    private void addChip(String label, final ExerciseType type) {
        TextView chip = new TextView(this);
        chip.setText(label);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        chip.setLayoutParams(params);
        chip.setMinHeight(dp(44));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(15), dp(7), dp(15), dp(7));
        chip.setTextSize(13);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setTag(type);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                filter = type;
                updateChipStyles();
                reload();
                scrollToRecords();
            }
        });
        chipGroup.addView(chip);
    }

    private void updateChipStyles() {
        for (int i = 0; i < chipGroup.getChildCount(); i++) {
            TextView chip = (TextView) chipGroup.getChildAt(i);
            Object tag = chip.getTag();
            boolean selected = (filter == null && tag == null) || (tag != null && filter == tag);
            chip.setSelected(selected);
            chip.setBackgroundResource(selected
                    ? R.drawable.history_bg_chip_selected : R.drawable.history_bg_chip);
            chip.setTextColor(getResources().getColor(selected
                    ? R.color.history_dark_text : R.color.history_muted));
            chip.setContentDescription(chip.getText() + (selected ? "，已选择" : "，筛选此动作"));
        }
    }

    private void reload() {
        allSessions.clear();
        allSessions.addAll(SessionStore.loadAll(this, user.getId()));
        sessions.clear();
        for (Session session : allSessions) {
            if (filter == null || ExerciseType.sameCatalog(session.getExerciseKey(), filter.getKey())) {
                sessions.add(session);
            }
        }
        growth = HistoryGrowth.from(allSessions, System.currentTimeMillis(), TimeZone.getDefault());
        tvSubtitle.setText(String.format(Locale.CHINA, "%s %s · %d 条足迹%s",
                user.getAvatar(), user.getName(), allSessions.size(),
                filter == null ? "" : " · 筛选 " + sessions.size() + " 条"));
        adapter.notifyDataSetChanged();
    }

    private void scrollToRecords() {
        // 位置 0 为星球摘要；位置 1 是第一条记录或空状态。
        recyclerView.scrollToPosition(1);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void openReplay(Session session) {
        Intent intent = new Intent(this, ReplayActivity.class);
        intent.putExtra(ReplayActivity.EXTRA_SESSION_ID, session.getId());
        intent.putExtra(ReplayActivity.EXTRA_USER_ID, session.getUserId());
        startActivity(intent);
    }

    private void confirmDelete(final Session session) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("删除这条足迹？")
                .setMessage("对应的视频与骨架回放也会被删除，且无法恢复。星星和徽章会按剩余记录重新计算。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        SessionStore.delete(HistoryActivity.this, session);
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showBadge(Badge badge) {
        String status = badge.isUnlocked()
                ? "已经收藏啦！每一次尝试，都值得为自己开心。"
                : "目前 " + badge.getProgress() + " / " + badge.getGoal()
                    + "。慢慢来，休息也是成长的一部分。";
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(badge.getIcon() + "  " + badge.getTitle())
                .setMessage(badge.getDescription() + "\n\n" + status
                        + "\n\n徽章只表示已保存的记录，不代表动作达标。")
                .setPositiveButton("好呀", null)
                .show();
    }

    private static String iconFor(ExerciseType type) {
        switch (type) {
            case HANDSTAND: return "🤸";
            case WALL_HANDSTAND: return "🧗";
            case CHAIR_PUSHUP: return "🪑";
            case PIKE_PUSHUP: return "🔺";
            case BAD_PUSHUP: return "💪";
            case PLANK: return "🧘";
            case SQUAT: return "🏋";
            case PULLUP: return "🧲";
            case WALL_SIT: return "🧱";
            case RUNNING: return "🏃";
            case PUSHUP:
            default: return "💪";
        }
    }

    private Bitmap loadThumb(Session session) {
        File file = SessionStore.resolveMedia(this, session.getUserId(), session.getThumbFile());
        if (file == null || !file.isFile()) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 4;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private boolean mediaExists(Session session, String fileName) {
        File file = SessionStore.resolveMedia(this, session.getUserId(), fileName);
        return file != null && file.isFile() && file.length() > 0;
    }

    private void bindDays(LinearLayout row) {
        row.removeAllViews();
        for (Day day : growth.getRecentDays()) {
            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER);
            column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            column.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView weekday = new TextView(this);
            weekday.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            weekday.setText(day.isToday() ? "今天" : WEEKDAYS[day.getDayOfWeek()]);
            weekday.setTextColor(getResources().getColor(R.color.history_muted));
            weekday.setTextSize(10);
            column.addView(weekday);

            TextView star = new TextView(this);
            star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams starParams = new LinearLayout.LayoutParams(dp(32), dp(32));
            starParams.topMargin = dp(6);
            star.setLayoutParams(starParams);
            star.setGravity(Gravity.CENTER);
            star.setBackgroundResource(day.hasRecord() ? R.drawable.history_bg_day_on
                    : R.drawable.history_bg_day_off);
            star.setText(day.hasRecord() ? "★" : "·");
            star.setTextColor(getResources().getColor(day.hasRecord()
                    ? R.color.history_dark_text : R.color.history_muted));
            star.setTextSize(day.hasRecord() ? 19 : 18);
            column.addView(star);

            TextView date = new TextView(this);
            date.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dateParams.topMargin = dp(5);
            date.setLayoutParams(dateParams);
            date.setText(String.format(Locale.CHINA, "%d/%d", day.getMonth(), day.getDayOfMonth()));
            date.setTextColor(getResources().getColor(R.color.history_subtle));
            date.setTextSize(10);
            column.addView(date);
            column.setContentDescription(day.getMonth() + "月" + day.getDayOfMonth() + "日，"
                    + (day.isToday() ? "今天" : WEEKDAYS[day.getDayOfWeek()])
                    + (day.hasRecord() ? "，有保存的运动记录，已点亮" : "，没有保存的运动记录"));
            row.addView(column);
        }
    }

    private void bindBadges(LinearLayout row) {
        row.removeAllViews();
        List<Badge> badges = growth.getBadges();
        for (int i = 0; i < badges.size(); i++) {
            final Badge badge = badges.get(i);
            View tile = LayoutInflater.from(this).inflate(R.layout.item_history_badge, row, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) params.leftMargin = dp(7);
            tile.setLayoutParams(params);
            tile.setBackgroundResource(badge.isUnlocked()
                    ? BADGE_BACKGROUNDS[i % BADGE_BACKGROUNDS.length] : R.drawable.history_bg_badge_locked);
            TextView icon = tile.findViewById(R.id.tvBadgeIcon);
            TextView title = tile.findViewById(R.id.tvBadgeTitle);
            TextView progress = tile.findViewById(R.id.tvBadgeProgress);
            icon.setText(badge.getIcon());
            icon.setAlpha(badge.isUnlocked() ? 1f : 0.65f);
            title.setText(badge.getTitle());
            progress.setText(badge.isUnlocked() ? "已收集 ✦"
                    : "进度 " + badge.getProgress() + " / " + badge.getGoal());
            progress.setTextColor(getResources().getColor(badge.isUnlocked()
                    ? R.color.history_gold : R.color.history_muted));
            tile.setContentDescription(badge.getTitle() + "。" + badge.getDescription()
                    + (badge.isUnlocked() ? "，已收集" : "，进度 " + badge.getProgress()
                    + " / " + badge.getGoal()) + "。点击查看。");
            tile.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showBadge(badge); }
            });
            row.addView(tile);
        }
    }

    private class SessionAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int HEADER = 0;
        private static final int RECORD = 1;
        private static final int EMPTY = 2;

        @Override public int getItemViewType(int position) {
            if (position == 0) return HEADER;
            return sessions.isEmpty() ? EMPTY : RECORD;
        }

        @NonNull
        @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == HEADER) return new HeaderHolder(inflater.inflate(R.layout.item_history_header, parent, false));
            if (viewType == EMPTY) return new EmptyHolder(inflater.inflate(R.layout.item_history_empty, parent, false));
            return new RecordHolder(inflater.inflate(R.layout.item_session, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderHolder) bindHeader((HeaderHolder) holder);
            else if (holder instanceof EmptyHolder) bindEmpty((EmptyHolder) holder);
            else bindRecord((RecordHolder) holder, sessions.get(position - 1));
        }

        @Override public int getItemCount() { return 1 + Math.max(1, sessions.size()); }

        private void bindHeader(HeaderHolder holder) {
            if (growth == null) return;
            holder.tvHeroName.setText(user.getAvatar() + " " + user.getName() + "的小星球");
            holder.tvJourneyTag.setText(growth.getUnlockedBadgeCount() == 0
                    ? "一起收集星光" : "已收藏 " + growth.getUnlockedBadgeCount() + " 枚徽章");
            holder.tvRecordCount.setText(String.valueOf(growth.getRecordCount()));
            holder.tvActiveDays.setText(String.valueOf(growth.getActiveDays()));
            holder.tvExerciseKinds.setText(String.valueOf(growth.getExerciseTypes()));
            holder.tvRecentCount.setText("点亮 " + growth.getRecentActiveDays() + " 天");
            holder.tvBadgeCount.setText(growth.getUnlockedBadgeCount() + " / " + growth.getBadges().size());
            holder.tvListCount.setText(sessions.size() + " 条");
            bindDays(holder.dayRow);
            bindBadges(holder.badgeRow);
        }

        private void bindEmpty(EmptyHolder holder) {
            final boolean noRecords = allSessions.isEmpty();
            holder.title.setText(noRecords ? "第一颗星星在等你" : "这个动作还没有足迹");
            holder.message.setText(noRecords ? "和家长选个喜欢的动作，留下第一条足迹吧。"
                    : "试试查看其他足迹，按照自己的节奏慢慢来。");
            holder.action.setText(noRecords ? "选择喜欢的运动" : "查看全部足迹");
            holder.action.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (noRecords) {
                        finish();
                    } else {
                        filter = null;
                        updateChipStyles();
                        reload();
                        scrollToRecords();
                    }
                }
            });
        }

        private void bindRecord(RecordHolder holder, final Session session) {
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
            String title = type.historyTitle() + (type == ExerciseType.CHAIR_PUSHUP
                    ? ("hands".equals(session.getVariant()) ? " · 手撑凳" : " · 脚垫凳") : "");
            String time = TimeUtils.formatFriendly(session.getStartedAt())
                    + (session.hasPersonalCalibration() ? " · 个人目标" : "");
            holder.tvTitle.setText(title);
            holder.tvTime.setText(time);
            if (type == ExerciseType.WALL_SIT) {
                holder.tvResult.setText(session.hasTotalHoldMs()
                        ? String.format(Locale.US, "累计 %.1f 秒 · 最长 %.1f 秒",
                        session.getTotalHoldMs() / 1000.0, session.getHoldMs() / 1000.0)
                        : "最长 " + session.getResultText());
            } else {
                holder.tvResult.setText((type == ExerciseType.BAD_PUSHUP ? "不标准 " : "")
                        + session.getResultText());
            }

            boolean hasVideo = mediaExists(session, session.getVideoFile());
            boolean hasPose = mediaExists(session, session.getPoseFile());
            holder.tvBadgeVideo.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
            holder.tvBadgePose.setVisibility(hasPose ? View.VISIBLE : View.GONE);
            holder.tvPlay.setVisibility(hasPose || hasVideo ? View.VISIBLE : View.GONE);
            holder.tvRecordAction.setText(hasPose || hasVideo ? "回看这次 ›" : "查看记录 ›");
            if (!type.isTimerExercise() && session.getBadReps() > 0) {
                holder.tvBadgeForm.setVisibility(View.VISIBLE);
                holder.tvBadgeForm.setText("待调整 " + session.getBadReps());
            } else if (type.isTimerExercise() && !Float.isNaN(session.getStability())
                    && !Float.isInfinite(session.getStability())
                    && session.getStability() >= 0 && session.getStability() <= 1) {
                holder.tvBadgeForm.setVisibility(View.VISIBLE);
                holder.tvBadgeForm.setText("稳定度 " + Math.round(session.getStability() * 100) + "%");
            } else {
                holder.tvBadgeForm.setVisibility(View.GONE);
            }

            holder.itemView.setContentDescription(title + "，" + time + "，" + holder.tvResult.getText()
                    + "。点击查看本次记录。");
            holder.btnDelete.setContentDescription("删除" + title + "记录");
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openReplay(session); }
            });
            holder.btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { confirmDelete(session); }
            });
        }

        class HeaderHolder extends RecyclerView.ViewHolder {
            final TextView tvHeroName, tvJourneyTag, tvRecordCount, tvActiveDays, tvExerciseKinds;
            final TextView tvRecentCount, tvBadgeCount, tvListCount;
            final LinearLayout dayRow, badgeRow;
            HeaderHolder(View view) {
                super(view);
                tvHeroName = view.findViewById(R.id.tvHeroName);
                tvJourneyTag = view.findViewById(R.id.tvJourneyTag);
                tvRecordCount = view.findViewById(R.id.tvRecordCount);
                tvActiveDays = view.findViewById(R.id.tvActiveDays);
                tvExerciseKinds = view.findViewById(R.id.tvExerciseKinds);
                tvRecentCount = view.findViewById(R.id.tvRecentCount);
                tvBadgeCount = view.findViewById(R.id.tvBadgeCount);
                tvListCount = view.findViewById(R.id.tvListCount);
                dayRow = view.findViewById(R.id.dayRow);
                badgeRow = view.findViewById(R.id.badgeRow);
            }
        }

        class EmptyHolder extends RecyclerView.ViewHolder {
            final TextView title, message, action;
            EmptyHolder(View view) {
                super(view);
                title = view.findViewById(R.id.tvEmptyTitle);
                message = view.findViewById(R.id.tvEmptyMessage);
                action = view.findViewById(R.id.btnEmptyAction);
            }
        }

        class RecordHolder extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final TextView tvThumbIcon, tvPlay, tvTitle, tvTime, tvBadgeVideo, tvBadgePose;
            final TextView tvBadgeForm, tvResult, tvRecordAction, btnDelete;
            RecordHolder(View view) {
                super(view);
                ivThumb = view.findViewById(R.id.ivThumb);
                tvThumbIcon = view.findViewById(R.id.tvThumbIcon);
                tvPlay = view.findViewById(R.id.tvPlay);
                tvTitle = view.findViewById(R.id.tvTitle);
                tvTime = view.findViewById(R.id.tvTime);
                tvBadgeVideo = view.findViewById(R.id.tvBadgeVideo);
                tvBadgePose = view.findViewById(R.id.tvBadgePose);
                tvBadgeForm = view.findViewById(R.id.tvBadgeForm);
                tvResult = view.findViewById(R.id.tvResult);
                tvRecordAction = view.findViewById(R.id.tvRecordAction);
                btnDelete = view.findViewById(R.id.btnDelete);
            }
        }
    }
}
