package com.zxw.fitness.data;

import android.content.Context;
import android.text.TextUtils;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.CalibrationChange;
import com.zxw.fitness.model.PersonalCalibration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 训练记录存储。
 *
 * <p>目录结构：{@code files/fitness/<userId>/sessions.json} 保存汇总记录，
 * {@code files/fitness/<userId>/media/} 保存骨架数据、视频与缩略图。所有数据按账号隔离。</p>
 */
public final class SessionStore {

    private static final String ROOT = "fitness";
    private static final String INDEX_FILE = "sessions.json";
    private static final String MEDIA_DIR = "media";

    private SessionStore() {
    }

    public static File userDir(Context context, String userId) {
        return new File(new File(context.getFilesDir(), ROOT), sanitize(userId));
    }

    public static File mediaDir(Context context, String userId) {
        File dir = new File(userDir(context, userId), MEDIA_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static long lastSessionId;

    /** 为新记录分配一个不会冲突的 ID（同一毫秒内连续两次训练也不会撞号）。 */
    public static synchronized long newSessionId() {
        long id = System.currentTimeMillis();
        if (id <= lastSessionId) {
            id = lastSessionId + 1;
        }
        lastSessionId = id;
        return id;
    }

    public static synchronized List<Session> loadAll(Context context, String userId) {
        List<Session> sessions = new ArrayList<>();
        File file = new File(userDir(context, userId), INDEX_FILE);
        if (!file.exists()) {
            return sessions;
        }
        String raw = readFile(file);
        if (raw == null || raw.length() == 0) {
            return sessions;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                Session s = fromJson(array.getJSONObject(i));
                if (s != null) {
                    sessions.add(s);
                }
            }
        } catch (JSONException ignored) {
            // 索引损坏时返回空列表，避免崩溃
        }
        Collections.sort(sessions, new Comparator<Session>() {
            @Override
            public int compare(Session a, Session b) {
                return Long.compare(b.getStartedAt(), a.getStartedAt());
            }
        });
        return sessions;
    }

    public static synchronized List<Session> loadByType(Context context, String userId, ExerciseType type) {
        List<Session> all = loadAll(context, userId);
        if (type == null) {
            return all;
        }
        List<Session> filtered = new ArrayList<>();
        for (Session s : all) {
            if (s.getExerciseType() == type) {
                filtered.add(s);
            }
        }
        return filtered;
    }

    public static synchronized void save(Context context, Session session) {
        if (session == null || TextUtils.isEmpty(session.getUserId())) {
            return;
        }
        List<Session> all = loadAll(context, session.getUserId());
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId() == session.getId()) {
                all.set(i, session);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            all.add(session);
        }
        Collections.sort(all, new Comparator<Session>() {
            @Override
            public int compare(Session a, Session b) {
                return Long.compare(b.getStartedAt(), a.getStartedAt());
            }
        });
        writeIndex(context, session.getUserId(), all);
    }

    /** 删除一条记录，同时删除它的骨架文件、视频与缩略图。 */
    public static synchronized void delete(Context context, Session session) {
        List<Session> all = loadAll(context, session.getUserId());
        List<Session> remain = new ArrayList<>();
        for (Session s : all) {
            if (s.getId() != session.getId()) {
                remain.add(s);
            }
        }
        writeIndex(context, session.getUserId(), remain);
        deleteQuietly(resolveMedia(context, session.getUserId(), session.getPoseFile()));
        deleteQuietly(resolveMedia(context, session.getUserId(), session.getVideoFile()));
        deleteQuietly(resolveMedia(context, session.getUserId(), session.getThumbFile()));
    }

    /** 删除账号的全部数据目录。 */
    public static synchronized void deleteAllData(Context context, String userId) {
        deleteRecursively(userDir(context, userId));
    }

    /** 把记录中保存的文件名解析成实际文件。 */
    public static File resolveMedia(Context context, String userId, String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return null;
        }
        return new File(mediaDir(context, userId), fileName);
    }

    /** media 目录内唯一的文件名前缀，例如 "1729999999000"。 */
    public static String mediaPrefix(long sessionId) {
        return String.valueOf(sessionId);
    }

    public static String sanitize(String userId) {
        if (TextUtils.isEmpty(userId)) {
            return "default";
        }
        return userId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void writeIndex(Context context, String userId, List<Session> sessions) {
        File dir = userDir(context, userId);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        JSONArray array = new JSONArray();
        for (Session s : sessions) {
            array.put(toJson(s));
        }
        writeFile(new File(dir, INDEX_FILE), array.toString());
    }

    private static JSONObject toJson(Session s) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", s.getId());
            o.put("userId", s.getUserId());
            o.put("exercise", s.getExerciseKey());
            o.put("variant", s.getVariant());
            o.put("startedAt", s.getStartedAt());
            o.put("durationMs", s.getDurationMs());
            o.put("reps", s.getReps());
            o.put("badReps", s.getBadReps());
            o.put("formErrors", s.getFormErrors());
            o.put("holdMs", s.getHoldMs());
            if (s.hasTotalHoldMs()) {
                o.put("totalHoldMs", s.getTotalHoldMs());
            }
            o.put("wallContact", s.isWallContact());
            o.put("poseFile", s.getPoseFile());
            o.put("videoFile", s.getVideoFile());
            o.put("thumbFile", s.getThumbFile());
            o.put("note", s.getNote());
            if (s.getInitialCalibration() != null) {
                o.put("initialCalibration", CalibrationJson.write(s.getInitialCalibration()));
            }
            if (!s.getCalibrationChanges().isEmpty()) {
                JSONArray events = new JSONArray();
                for (CalibrationChange change : s.getCalibrationChanges()) {
                    JSONObject event = new JSONObject();
                    event.put("frameIndex", change.frameIndex);
                    event.put("calibration", CalibrationJson.write(change.calibration));
                    events.put(event);
                }
                o.put("calibrationChanges", events);
            }
            if (!Float.isNaN(s.getStability())) {
                o.put("stability", s.getStability());
            }
        } catch (JSONException ignored) {
            // key 均为字符串，不会抛异常
        }
        return o;
    }

    private static Session fromJson(JSONObject o) {
        try {
            Session s = new Session();
            s.setId(o.getLong("id"));
            s.setUserId(o.getString("userId"));
            s.setExerciseKey(o.optString("exercise", ExerciseType.PUSHUP.getKey()));
            s.setVariant(o.optString("variant", ""));
            s.setStartedAt(o.optLong("startedAt", 0));
            s.setDurationMs(o.optLong("durationMs", 0));
            s.setReps(o.optInt("reps", 0));
            s.setBadReps(o.optInt("badReps", 0));
            s.setFormErrors(o.optInt("formErrors", 0));
            s.setHoldMs(o.optLong("holdMs", 0));
            // 旧 JSON 中没有 totalHoldMs；保留 -1，而不是误把最长单组当累计。
            s.setTotalHoldMs(o.optLong("totalHoldMs", -1));
            s.setWallContact(o.optBoolean("wallContact", false));
            s.setPoseFile(o.optString("poseFile", null));
            s.setVideoFile(o.optString("videoFile", null));
            s.setThumbFile(o.optString("thumbFile", null));
            s.setNote(o.optString("note", ""));
            s.setStability((float) o.optDouble("stability", Double.NaN));
            s.setInitialCalibration(CalibrationJson.read(o.optJSONObject("initialCalibration")));
            JSONArray events = o.optJSONArray("calibrationChanges");
            if (events != null) {
                for (int i = 0; i < events.length(); i++) {
                    JSONObject event = events.optJSONObject(i);
                    if (event == null) continue;
                    PersonalCalibration calibration = CalibrationJson.read(event.optJSONObject("calibration"));
                    s.addCalibrationChange(event.optInt("frameIndex", -1), calibration);
                }
            }
            return s;
        } catch (JSONException e) {
            return null;
        }
    }

    private static String readFile(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(reader);
        }
    }

    private static void writeFile(File file, String content) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(content.getBytes("UTF-8"));
            out.flush();
        } catch (IOException ignored) {
            // 存储失败时不影响训练继续进行
        } finally {
            closeQuietly(out);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
