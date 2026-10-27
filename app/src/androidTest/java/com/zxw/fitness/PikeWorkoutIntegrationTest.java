package com.zxw.fitness;

import android.content.Context;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import com.zxw.fitness.analysis.AlgorithmVersion;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.WorkoutRecorder;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.RecordedTimeline;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** 真实记录器、账号/JSON、骨架和缓存的设备测试；不包含相机或人体视频。 */
@RunWith(AndroidJUnit4.class)
public class PikeWorkoutIntegrationTest {
    private Context context;
    private UserProfile original, child;
    private long time;
    private final List<Integer> live = new ArrayList<>();

    @Before public void setup() {
        context = InstrumentationRegistry.getTargetContext();
        original = UserStore.getCurrent(context);
        child = UserStore.addUser(context, "折刀测试");
    }

    @After public void cleanup() {
        if (child != null) UserStore.delete(context, child);
        if (original != null && UserStore.getById(context, original.getId()) != null) {
            UserStore.setCurrent(context, original);
        }
    }

    private void feed(WorkoutRecorder recorder, float angle) {
        float drop = Math.max(0f, (170f - angle) / 80f * .09f);
        recorder.onPose(pike(time, angle, drop), 720, 1280, 0f);
        time += 100;
        live.add(recorder.getRepAnalyzer().getGoodReps());
    }

    private void hold(WorkoutRecorder recorder, float angle, int count) {
        for (int i = 0; i < count; i++) feed(recorder, angle);
    }

    @Test public void personalGoalIsSavedAndRecordedFramesReplayWithoutRecount() {
        Session session = new Session(); session.setId(SessionStore.newSessionId());
        session.setUserId(child.getId()); session.setExerciseType(ExerciseType.PIKE_PUSHUP);
        session.setStartedAt(1000);
        WorkoutRecorder recorder = new WorkoutRecorder(context, session, true, null);
        hold(recorder, 170, 8);
        for (float angle : new float[]{150,145,140,135,135,135}) hold(recorder, angle, 3);
        assertNull("稳定后校准一次", recorder.calibratePersonal());
        live.set(live.size() - 1, recorder.getRepAnalyzer().getGoodReps());
        assertNotNull("同一位置不能重复校准", recorder.calibratePersonal());
        assertNotNull(UserStore.getById(context, child.getId())
                .getCalibration(ExerciseType.PIKE_PUSHUP, ""));
        assertNull(UserStore.getById(context, child.getId())
                .getCalibration(ExerciseType.PUSHUP, ""));
        for (float angle : new float[]{140,145,150,170,170,150,145,140,135,135,
                135,140,145,150,170,170}) hold(recorder, angle, 3);
        recorder.finish(100000);
        assertEquals(2, session.getReps());
        assertEquals(0, session.getBadReps());
        assertEquals(AlgorithmVersion.PIKE_CURRENT, session.getAlgorithmVersion());
        assertEquals(1, session.getCalibrationChanges().size());
        SessionStore.save(context, session);
        Session restored = SessionStore.loadAll(context, child.getId()).get(0);
        assertEquals(ExerciseType.PIKE_PUSHUP, restored.getExerciseType());
        assertEquals(AlgorithmVersion.PIKE_CURRENT, restored.getAlgorithmVersion());
        assertEquals(1, restored.getCalibrationChanges().size());
        File pose = SessionStore.resolveMedia(context, child.getId(), restored.getPoseFile());
        File cache = SessionStore.resolveMedia(context, child.getId(), restored.getTimelineFile());
        PoseLog log = PoseLog.load(pose);
        ReplayTimeline timeline = RecordedTimeline.load(cache, pose, log, restored);
        assertNotNull(timeline);
        ReplayTimeline recomputed = ReplayTimeline.build(log.getFrames(), restored);
        assertEquals(live.size(), timeline.size());
        for (int i = 0; i < live.size(); i++) {
            assertEquals("缓存 frame " + i, (int) live.get(i), timeline.getGoodRepsAt(i));
            assertEquals("骨架 frame " + i, (int) live.get(i), recomputed.getGoodRepsAt(i));
        }
        SessionStore.delete(context, restored);
        assertFalse(pose.exists()); assertFalse(cache.exists());
    }

    @Test public void oldBadModeStaysReadableAndGroupsOnlyWithNormalPushup() {
        Session legacy = new Session(); legacy.setId(SessionStore.newSessionId());
        legacy.setUserId(child.getId()); legacy.setExerciseType(ExerciseType.BAD_PUSHUP);
        legacy.setReps(3); legacy.setBadReps(11); legacy.setStartedAt(1);
        SessionStore.save(context, legacy);
        Session normal = new Session(); normal.setId(SessionStore.newSessionId());
        normal.setUserId(child.getId()); normal.setExerciseType(ExerciseType.PUSHUP);
        normal.setReps(5); normal.setStartedAt(2); SessionStore.save(context, normal);
        Session pike = new Session(); pike.setId(SessionStore.newSessionId());
        pike.setUserId(child.getId()); pike.setExerciseType(ExerciseType.PIKE_PUSHUP);
        pike.setReps(7); pike.setStartedAt(3); SessionStore.save(context, pike);
        List<Session> group = SessionStore.loadByType(context, child.getId(), ExerciseType.PUSHUP);
        assertEquals(2, group.size());
        Session reloadedLegacy = group.get(1);
        assertEquals(ExerciseType.BAD_PUSHUP, reloadedLegacy.getExerciseType());
        assertEquals(11, reloadedLegacy.getResultValue(), 0);
        assertEquals(3, reloadedLegacy.getCatalogBestValue(), 0);
        assertEquals("不标准 11 次（旧记录）", reloadedLegacy.getResultSummary());
        assertEquals(1, SessionStore.loadByType(context, child.getId(), ExerciseType.PIKE_PUSHUP).size());
        assertEquals(1, SessionStore.loadByType(context, child.getId(), ExerciseType.BAD_PUSHUP).size());
    }

    private static PoseFrame pike(long t, float angle, float drop) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT], y = x.clone(), z = x.clone(), c = x.clone();
        float sx = .38f, sy = .55f + drop, wx = .33f, wy = .78f;
        float dx = wx - sx, dy = wy - sy;
        float length = (float) Math.hypot(dx, dy);
        float bulge = (float) (length / (2 * Math.tan(Math.toRadians(angle) / 2)));
        float ex = (sx + wx) / 2 - dy / length * bulge;
        float ey = (sy + wy) / 2 + dx / length * bulge;
        float[][] xy = {{sx,sy}, {ex,ey}, {wx,wy}, {.57f,.35f}, {.675f,.55f}, {.78f,.75f}};
        int[] left = {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE};
        int[] right = {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
        for (int i = 0; i < left.length; i++) for (int id : new int[]{left[i], right[i]}) {
            x[id] = xy[i][0]; y[id] = xy[i][1]; c[id] = .9f;
        }
        x[PoseFrame.NOSE] = .35f; y[PoseFrame.NOSE] = sy + .03f; c[PoseFrame.NOSE] = .9f;
        return PoseFrame.of(t, x, y, z, c);
    }
}
