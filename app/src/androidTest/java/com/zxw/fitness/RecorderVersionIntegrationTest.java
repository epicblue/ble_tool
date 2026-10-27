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

/** 使用真实记录器、个人校准、二进制文件和 JSON；不包含相机或 ML Kit。 */
@RunWith(AndroidJUnit4.class)
public class RecorderVersionIntegrationTest {
    private Context context;
    private UserProfile original, temporary;
    private long time;
    private final List<Integer> live = new ArrayList<>();

    @Before public void setUp() {
        context = InstrumentationRegistry.getTargetContext();
        original = UserStore.getCurrent(context);
        temporary = UserStore.addUser(context, "算法缓存测试");
    }

    @After public void tearDown() {
        if (temporary != null) UserStore.delete(context, temporary);
        if (original != null && UserStore.getById(context, original.getId()) != null) {
            UserStore.setCurrent(context, original);
        }
    }

    private void feed(WorkoutRecorder recorder, float angle, float drop) {
        recorder.onPose(squat(time, angle, drop, 0), 720, 1280, 1.5f);
        time += 66;
        live.add(recorder.getRepAnalyzer().getGoodReps());
    }

    @Test public void recordingPersistsVersionCalibrationAndExactFrameResultsAndDeletesAllFiles() {
        Session s = new Session();
        s.setId(SessionStore.newSessionId()); s.setUserId(temporary.getId());
        s.setExerciseType(ExerciseType.SQUAT); s.setStartedAt(1000);
        WorkoutRecorder r = new WorkoutRecorder(context, s, true, null);
        for (int i = 0; i < 8; i++) feed(r, 175, 0);
        for (float angle : new float[]{160, 150, 140, 130, 130, 130}) {
            for (int i = 0; i < 3; i++) feed(r, angle, angle < 150 ? .12f : 0);
        }
        assertNull("稳定后允许个人校准", r.calibratePersonal());
        live.set(live.size() - 1, r.getRepAnalyzer().getGoodReps());
        assertNotNull("同一动作不能重复校准", r.calibratePersonal());
        for (float angle : new float[]{140, 150, 160, 175, 175, 160, 150,
                140, 130, 130, 130, 140, 150, 160, 175, 175}) {
            for (int i = 0; i < 3; i++) feed(r, angle, angle < 150 ? .12f : 0);
        }
        r.finish(100000);
        assertEquals(2, s.getReps());
        assertEquals(AlgorithmVersion.CURRENT, s.getAlgorithmVersion());
        assertNotNull(s.getTimelineFile());
        assertEquals(1, s.getCalibrationChanges().size());
        File pose = SessionStore.resolveMedia(context, temporary.getId(), s.getPoseFile());
        File cache = SessionStore.resolveMedia(context, temporary.getId(), s.getTimelineFile());
        assertTrue(pose.isFile()); assertTrue(cache.isFile());
        SessionStore.save(context, s);
        Session restored = SessionStore.loadAll(context, temporary.getId()).get(0);
        assertEquals(AlgorithmVersion.CURRENT, restored.getAlgorithmVersion());
        assertEquals(s.getTimelineFile(), restored.getTimelineFile());
        assertEquals(1, restored.getCalibrationChanges().size());
        PoseLog log = PoseLog.load(pose);
        ReplayTimeline timeline = RecordedTimeline.load(cache, pose, log, restored);
        assertNotNull("JSON 往返后缓存仍匹配", timeline);
        assertEquals(live.size(), timeline.size());
        for (int i = 0; i < live.size(); i++) assertEquals("frame " + i, (int) live.get(i), timeline.getGoodRepsAt(i));
        SessionStore.delete(context, restored);
        assertFalse(pose.exists()); assertFalse(cache.exists());
        assertTrue(SessionStore.loadAll(context, temporary.getId()).isEmpty());
    }
    @Test public void missingTimelineDoesNotBlockPoseRecordingOrMarkOldSessionsAsCurrent() {
        Session s = new Session(); s.setId(SessionStore.newSessionId()); s.setUserId(temporary.getId());
        s.setExerciseType(ExerciseType.SQUAT); s.setStartedAt(1000);
        File inaccessible = new File(SessionStore.mediaDir(context, temporary.getId()),
                s.getId() + "_timeline.dat");
        assertTrue(inaccessible.mkdir());
        WorkoutRecorder r = new WorkoutRecorder(context, s, true, null);
        for (int i = 0; i < 10; i++) feed(r, 175, 0);
        r.finish(2000);
        assertEquals(AlgorithmVersion.CURRENT, s.getAlgorithmVersion());
        assertNotNull("骨架不应因缓存不可写而丢失", s.getPoseFile());
        assertNull(s.getTimelineFile());
        Session old = new Session(); old.setId(SessionStore.newSessionId());
        old.setUserId(temporary.getId()); old.setExerciseType(ExerciseType.SQUAT);
        SessionStore.save(context, old);
        Session restored = SessionStore.loadAll(context, temporary.getId()).get(0);
        assertNull("旧版没有版本，不能视为同版", restored.getAlgorithmVersion());
        assertNull(restored.getTimelineFile());
    }

    private static PoseFrame squat(long ts, float knee, float drop, float dx) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        float hipY = .42f + drop * .25f;
        float shoulderY = hipY - .20f;
        float ankleY = .82f;
        float half = (float) Math.toRadians(Math.max(10f, Math.min(178f, knee)) / 2f);
        float forward = (float) (((ankleY - hipY) / 2f) * Math.tan(Math.PI / 2 - half));
        for (int side = 0; side < 2; side++) {
            int[] ids = side == 0
                    ? new int[]{PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                            PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE}
                    : new int[]{PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                            PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
            float[] yy = {shoulderY, shoulderY + .10f, shoulderY + .18f,
                    hipY, (hipY + ankleY) / 2f, ankleY};
            for (int i = 0; i < ids.length; i++) {
                x[ids[i]] = .45f + dx + (i == 4 ? forward : 0f);
                y[ids[i]] = yy[i];
                c[ids[i]] = .9f;
            }
        }
        x[PoseFrame.NOSE] = .45f + dx;
        y[PoseFrame.NOSE] = shoulderY - .07f;
        c[PoseFrame.NOSE] = .9f;
        return PoseFrame.of(ts, x, y, z, c);
    }
}
