package com.zxw.fitness.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.zxw.fitness.model.UserProfile;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 账号（使用者）存储：保存在 SharedPreferences 中，支持新增、重命名、删除与切换。
 */
public final class UserStore {

    private static final String PREFS = "fitness_users";
    private static final String KEY_USERS = "users";
    private static final String KEY_CURRENT = "current_user";

    private static final String[] AVATARS = {"🐯", "🐼", "🦊", "🐨", "🦁", "🐸", "🐵", "🐰"};

    private UserStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读取全部账号，若无任何账号则创建默认账号。 */
    public static List<UserProfile> loadAll(Context context) {
        List<UserProfile> users = new ArrayList<>();
        String raw = prefs(context).getString(KEY_USERS, null);
        if (raw != null) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    UserProfile user = new UserProfile(
                            o.getString("id"),
                            o.optString("name", "训练者"),
                            o.optLong("createdAt", System.currentTimeMillis()),
                            o.optString("avatar", "🐯"));
                    JSONArray calibrations = o.optJSONArray("calibrations");
                    if (calibrations != null) {
                        for (int j = 0; j < calibrations.length(); j++) {
                            user.setCalibration(CalibrationJson.read(calibrations.optJSONObject(j)));
                        }
                    }
                    users.add(user);
                }
            } catch (JSONException ignored) {
                // 数据损坏时回退到默认账号
            }
        }
        if (users.isEmpty()) {
            UserProfile def = new UserProfile(UserProfile.DEFAULT_ID, "我",
                    System.currentTimeMillis(), AVATARS[0]);
            users.add(def);
            persist(context, users);
            setCurrentId(context, def.getId());
        }
        return users;
    }

    /** 当前账号。 */
    public static UserProfile getCurrent(Context context) {
        List<UserProfile> users = loadAll(context);
        String id = prefs(context).getString(KEY_CURRENT, null);
        for (UserProfile u : users) {
            if (u.getId().equals(id)) {
                return u;
            }
        }
        // 当前账号被删除或首次启动
        setCurrentId(context, users.get(0).getId());
        return users.get(0);
    }

    public static UserProfile getById(Context context, String userId) {
        for (UserProfile user : loadAll(context)) {
            if (user.getId().equals(userId)) return user;
        }
        return null;
    }

    public static void setCurrent(Context context, UserProfile user) {
        setCurrentId(context, user.getId());
    }

    private static void setCurrentId(Context context, String id) {
        prefs(context).edit().putString(KEY_CURRENT, id).apply();
    }

    /** 新增账号并自动切换为当前账号。 */
    public static UserProfile addUser(Context context, String name) {
        List<UserProfile> users = loadAll(context);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() == 0) {
            trimmed = "训练者 " + (users.size() + 1);
        }
        UserProfile user = new UserProfile(
                "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10),
                trimmed,
                System.currentTimeMillis(),
                AVATARS[users.size() % AVATARS.length]);
        users.add(user);
        persist(context, users);
        setCurrentId(context, user.getId());
        return user;
    }

    public static void rename(Context context, UserProfile user, String newName) {
        if (newName == null || newName.trim().length() == 0) {
            return;
        }
        List<UserProfile> users = loadAll(context);
        for (UserProfile u : users) {
            if (u.getId().equals(user.getId())) {
                u.setName(newName.trim());
            }
        }
        persist(context, users);
    }

    /** 同步保存：只有落盘成功后训练页才确认校准和计数。 */
    public static synchronized boolean saveCalibration(Context context, String userId,
                                                       PersonalCalibration calibration) {
        if (calibration == null || !calibration.isValid()) return false;
        List<UserProfile> users = loadAll(context);
        for (UserProfile user : users) {
            if (user.getId().equals(userId)) {
                user.setCalibration(calibration);
                return persistNow(context, users);
            }
        }
        return false;
    }

    public static synchronized boolean clearCalibration(Context context, String userId,
                                                        ExerciseType type, String variant) {
        List<UserProfile> users = loadAll(context);
        for (UserProfile user : users) {
            if (user.getId().equals(userId)) {
                user.clearCalibration(type, variant);
                return persistNow(context, users);
            }
        }
        return false;
    }

    /**
     * 删除账号及其全部训练数据；若删除的是当前账号，则切换到其他账号。
     *
     * @return true 表示删除成功（默认账号不可删除）
     */
    public static boolean delete(Context context, UserProfile user) {
        if (!user.isDeletable()) {
            return false;
        }
        List<UserProfile> users = loadAll(context);
        List<UserProfile> remain = new ArrayList<>();
        for (UserProfile u : users) {
            if (!u.getId().equals(user.getId())) {
                remain.add(u);
            }
        }
        persist(context, remain);
        SessionStore.deleteAllData(context, user.getId());
        String current = prefs(context).getString(KEY_CURRENT, null);
        if (user.getId().equals(current) && !remain.isEmpty()) {
            setCurrentId(context, remain.get(0).getId());
        }
        return true;
    }

    private static void persist(Context context, List<UserProfile> users) {
        prefs(context).edit().putString(KEY_USERS, encode(users)).apply();
    }

    private static boolean persistNow(Context context, List<UserProfile> users) {
        SharedPreferences preferences = prefs(context);
        String previous = preferences.getString(KEY_USERS, null);
        if (preferences.edit().putString(KEY_USERS, encode(users)).commit()) return true;
        // commit 失败时 SharedPreferences 的内存副本可能已经变化；恢复原设置。
        SharedPreferences.Editor restore = preferences.edit();
        if (previous == null) restore.remove(KEY_USERS); else restore.putString(KEY_USERS, previous);
        restore.apply();
        return false;
    }

    private static String encode(List<UserProfile> users) {
        JSONArray array = new JSONArray();
        for (UserProfile u : users) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", u.getId());
                o.put("name", u.getName());
                o.put("createdAt", u.getCreatedAt());
                o.put("avatar", u.getAvatar());
                JSONArray calibrations = new JSONArray();
                for (PersonalCalibration c : u.getCalibrations()) {
                    calibrations.put(CalibrationJson.write(c));
                }
                o.put("calibrations", calibrations);
            } catch (JSONException ignored) {
                // 不可能发生：key 均为字符串
            }
            array.put(o);
        }
        return array.toString();
    }
}
