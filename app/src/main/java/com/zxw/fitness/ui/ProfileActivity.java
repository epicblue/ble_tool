package com.zxw.fitness.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zxw.R;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.util.TimeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 账号管理：切换 / 新建 / 重命名 / 删除。每个账号的训练记录完全独立。
 */
public class ProfileActivity extends Activity {

    private RecyclerView recyclerView;
    private UserAdapter adapter;
    private final List<UserProfile> users = new ArrayList<>();
    private String currentId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.btnAddUser).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddUserDialog();
            }
        });

        findViewById(R.id.btnCalibrationSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCalibrations(); }
        });

        recyclerView = findViewById(R.id.rvUsers);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UserAdapter();
        recyclerView.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        users.clear();
        users.addAll(UserStore.loadAll(this));
        currentId = UserStore.getCurrent(this).getId();
        adapter.notifyDataSetChanged();
    }

    private void showCalibrations() {
        final UserProfile current = UserStore.getCurrent(this);
        final List<PersonalCalibration> settings = current.getCalibrations();
        if (settings.isEmpty()) {
            new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(current.getName() + " · 个人校准")
                    .setMessage("尚未设置个人目标。开始俯卧撑、折凳俯卧撑、折刀俯卧撑、深蹲或引体向上后，"
                            + "保持在当前能做到的最好位置，点击训练页的「个人校准 +1」。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        String[] labels = new String[settings.size()];
        for (int i = 0; i < settings.size(); i++) {
            labels[i] = settings.get(i).getTitle();
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(current.getName() + " · 个人校准")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        showCalibrationDetails(current, settings.get(which));
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    private void showCalibrationDetails(final UserProfile profile, final PersonalCalibration calibration) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(calibration.getTitle())
                .setMessage(calibration.describe() + "\n\n个人目标仅表示你确认的当前能力，"
                        + "并不代表标准或安全姿势。如有不适请停止训练。"
                        + "\n\n恢复默认只影响以后的训练，历史回放保留当时的目标。")
                .setPositiveButton("恢复默认", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        boolean ok = UserStore.clearCalibration(ProfileActivity.this, profile.getId(),
                                calibration.getType(), calibration.getVariant());
                        Toast.makeText(ProfileActivity.this, ok ? "已恢复默认目标" : "保存失败，请重试",
                                Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .setNegativeButton("关闭", null).show();
    }

    private void showAddUserDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入使用者名字，例如：小明");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(getResources().getColor(R.color.fit_text));
        input.setHintTextColor(getResources().getColor(R.color.fit_text_faint));
        FrameLayout container = new FrameLayout(this);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("新建账号")
                .setView(container)
                .setPositiveButton("创建并切换", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        UserStore.addUser(ProfileActivity.this, input.getText().toString());
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showUserActions(final UserProfile user) {
        List<String> actions = new ArrayList<>();
        actions.add("切换到此账号");
        actions.add("重命名");
        if (user.isDeletable()) {
            actions.add("删除账号及训练数据");
        }
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle(user.getAvatar() + " " + user.getName())
                .setItems(actions.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            switchTo(user);
                        } else if (which == 1) {
                            showRenameDialog(user);
                        } else {
                            confirmDelete(user);
                        }
                    }
                })
                .show();
    }

    private void switchTo(UserProfile user) {
        UserStore.setCurrent(this, user);
        Toast.makeText(this, "已切换到「" + user.getName() + "」", Toast.LENGTH_SHORT).show();
        reload();
    }

    private void showRenameDialog(final UserProfile user) {
        final EditText input = new EditText(this);
        input.setText(user.getName());
        input.setSingleLine(true);
        input.setTextColor(getResources().getColor(R.color.fit_text));
        FrameLayout container = new FrameLayout(this);
        int padding = (int) (getResources().getDisplayMetrics().density * 20);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input);

        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("重命名账号")
                .setView(container)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        UserStore.rename(ProfileActivity.this, user, input.getText().toString());
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final UserProfile user) {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("删除账号")
                .setMessage("将同时删除「" + user.getName() + "」的全部个人校准、训练记录、视频与骨架数据，且无法恢复。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        UserStore.delete(ProfileActivity.this, user);
                        reload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private class UserAdapter extends RecyclerView.Adapter<UserAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_user, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            final UserProfile user = users.get(position);
            List<Session> sessions = SessionStore.loadAll(ProfileActivity.this, user.getId());
            holder.tvAvatar.setText(user.getAvatar());
            holder.tvName.setText(user.getName());
            holder.tvCurrent.setVisibility(user.getId().equals(currentId) ? View.VISIBLE : View.GONE);

            if (sessions.isEmpty()) {
                holder.tvStats.setText("还没有训练记录");
            } else {
                long last = sessions.get(0).getStartedAt();
                holder.tvStats.setText(String.format(Locale.CHINA, "%d 次训练 · 最近 %s",
                        sessions.size(), TimeUtils.formatFriendly(last)));
            }

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!user.getId().equals(currentId)) {
                        switchTo(user);
                    }
                }
            });
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showUserActions(user);
                    return true;
                }
            });
        }

        @Override
        public int getItemCount() {
            return users.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final TextView tvAvatar;
            final TextView tvName;
            final TextView tvCurrent;
            final TextView tvStats;

            Holder(View itemView) {
                super(itemView);
                tvAvatar = itemView.findViewById(R.id.tvAvatar);
                tvName = itemView.findViewById(R.id.tvName);
                tvCurrent = itemView.findViewById(R.id.tvCurrent);
                tvStats = itemView.findViewById(R.id.tvStats);
            }
        }
    }
}
