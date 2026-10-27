package com.zxw.fitness.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 体能训练入口：显示当前账号、可选动作以及各动作的最好成绩。
 */
public class ExerciseListActivity extends Activity {

    private static final ExerciseType[] TYPES = ExerciseType.values();
    private static final String[] EMOJIS = {"🤸", "🧗", "🪑", "💪", "⚠️", "🧘", "🏋", "🧲", "🧱", "🏃"};

    private UserProfile currentUser;
    private ExerciseAdapter adapter;
    private TextView tvAvatar;
    private TextView tvUserName;
    private TextView tvUserStats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exercise_list);

        tvAvatar = findViewById(R.id.tvAvatar);
        tvUserName = findViewById(R.id.tvUserName);
        tvUserStats = findViewById(R.id.tvUserStats);

        findViewById(R.id.accountBar).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ExerciseListActivity.this, ProfileActivity.class));
            }
        });
        findViewById(R.id.btnHistory).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(ExerciseListActivity.this, HistoryActivity.class));
            }
        });

        RecyclerView recyclerView = findViewById(R.id.rvExercises);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ExerciseAdapter();
        recyclerView.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        currentUser = UserStore.getCurrent(this);
        tvAvatar.setText(currentUser.getAvatar());
        tvUserName.setText(currentUser.getName());
        List<Session> sessions = SessionStore.loadAll(this, currentUser.getId());
        int total = sessions.size();
        long totalMinutes = 0;
        for (Session s : sessions) {
            totalMinutes += s.getDurationMs();
        }
        tvUserStats.setText(total == 0
                ? "点击切换账号 · 还没有训练记录"
                : String.format(Locale.CHINA, "点击切换账号 · 共 %d 次训练 · %d 分钟",
                total, Math.max(1, totalMinutes / 60000)));
        adapter.setStats(buildStats(sessions));
        adapter.notifyDataSetChanged();
    }

    private Map<String, Stats> buildStats(List<Session> sessions) {
        Map<String, Stats> map = new HashMap<>();
        for (Session s : sessions) {
            String key = s.getExerciseKey();
            if (key == null) {
                continue;
            }
            Stats stats = map.get(key);
            if (stats == null) {
                stats = new Stats();
                map.put(key, stats);
            }
            stats.count++;
            double value = s.getResultValue();
            stats.best = Math.max(stats.best, value);
            if (!Float.isNaN(s.getStability())) {
                stats.stability = Math.max(stats.stability, s.getStability());
            }
            if (s.getStartedAt() > stats.lastTime) {
                stats.lastTime = s.getStartedAt();
            }
        }
        return map;
    }

    private static class Stats {
        int count;
        double best;
        long lastTime;
        float stability;
    }

    private void startTraining(ExerciseType type, String variant) {
        Intent intent = new Intent(this, TrainingActivity.class);
        intent.putExtra(TrainingActivity.EXTRA_EXERCISE, type.getKey());
        intent.putExtra(TrainingActivity.EXTRA_VARIANT, variant);
        startActivity(intent);
    }

    private void onExerciseClicked(final ExerciseType type) {
        if (type == ExerciseType.CHAIR_PUSHUP) {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("折凳俯卧撑 · 选择姿势")
                    .setItems(new String[]{"双手撑凳（上斜，较轻松）", "双脚垫凳（下斜，更吃力）"},
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    startTraining(type, which == 0 ? "hands" : "feet");
                                }
                            })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        startTraining(type, "");
    }

    private class ExerciseAdapter extends RecyclerView.Adapter<ExerciseAdapter.Holder> {

        private Map<String, Stats> stats = new HashMap<>();

        void setStats(Map<String, Stats> stats) {
            this.stats = stats;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_exercise, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final ExerciseType type = TYPES[position];
            holder.tvEmoji.setText(EMOJIS[position]);
            holder.tvTitle.setText(type.getTitle());
            holder.tvUnit.setText(type.isTimerExercise() ? "计时" : "计数");
            holder.tvDesc.setText(type.getDescription());

            Stats s = stats.get(type.getKey());
            if (s == null || s.count == 0) {
                holder.tvBest.setText("暂无记录 · 点击开始");
                holder.tvBest.setTextColor(getColorCompat(R.color.fit_text_faint));
            } else {
                String bestText;
                if (type.isTimerExercise()) {
                    bestText = String.format(Locale.US,
                            type == ExerciseType.WALL_SIT ? "最佳单组 %.1f 秒" : "最佳 %.1f 秒", s.best);
                } else if (type == ExerciseType.BAD_PUSHUP) {
                    bestText = String.format(Locale.US, "最多不标准 %d 次", (int) s.best);
                } else {
                    bestText = String.format(Locale.US, "最佳 %d %s", (int) s.best,
                            type.getUnitLabel());
                }
                if (s.stability > 0) {
                    bestText = bestText + String.format(Locale.US, " · 稳定度 %d%%",
                            (int) (s.stability * 100));
                }
                holder.tvBest.setText(String.format(Locale.CHINA, "%s · 共 %d 次训练", bestText, s.count));
                holder.tvBest.setTextColor(getColorCompat(R.color.fit_accent));
            }
            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onExerciseClicked(type);
                }
            });
        }

        @Override
        public int getItemCount() {
            return TYPES.length;
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView tvEmoji;
            final TextView tvTitle;
            final TextView tvUnit;
            final TextView tvDesc;
            final TextView tvBest;

            Holder(View itemView) {
                super(itemView);
                tvEmoji = itemView.findViewById(R.id.tvEmoji);
                tvTitle = itemView.findViewById(R.id.tvTitle);
                tvUnit = itemView.findViewById(R.id.tvUnit);
                tvDesc = itemView.findViewById(R.id.tvDesc);
                tvBest = itemView.findViewById(R.id.tvBest);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private int getColorCompat(int resId) {
        return getResources().getColor(resId);
    }
}
