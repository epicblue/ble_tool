package com.zxw.fitness;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.WorkoutRecorder;
import com.zxw.fitness.data.PoseLog;
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

/** 合成关键点经过真实 WorkoutRecorder、个人校准、二进制文件及回放；不运行相机或 ML Kit。 */
@RunWith(AndroidJUnit4.class)
public class SquatPanRecorderTest {
    private Context context;
    private UserProfile original;
    private UserProfile temporary;
    private long time;
    private final List<Integer> good = new ArrayList<>();
    private final List<Integer> bad = new ArrayList<>();

    @Before public void setUp() {
        context = InstrumentationRegistry.getTargetContext();
        original = UserStore.getCurrent(context);
        temporary = UserStore.addUser(context, "深蹲横移测试");
    }

    @After public void tearDown() {
        if (temporary != null) UserStore.delete(context, temporary);
        if (original != null && UserStore.getById(context, original.getId()) != null) {
            UserStore.setCurrent(context, original);
        }
    }

    private Session session(ExerciseType type) {
        Session s = new Session();
        s.setId(SessionStore.newSessionId());
        s.setUserId(temporary.getId());
        s.setExerciseType(type);
        s.setStartedAt(1000);
        return s;
    }

    private void feed(WorkoutRecorder recorder, float angle, float drop, float dx) {
        recorder.onPose(squat(time, angle, drop, dx), 720, 1280, 0f);
        time += 66;
        good.add(recorder.getRepAnalyzer().getGoodReps());
        bad.add(recorder.getRepAnalyzer().getBadReps());
    }

    @Test public void recorderPreservesCountsCalibrationAndPoseReplayAfterPan() {
        Session s = session(ExerciseType.SQUAT);
        WorkoutRecorder recorder = new WorkoutRecorder(context, s, true, null);
        for (int i = 0; i < 8; i++) feed(recorder, 168f, 0f, -.12f);
        for (int i = 0; i < 9; i++) feed(recorder, 168f - (i + 1) * 8f, (i + 1) * .06f, -.12f);
        for (int i = 0; i < 32; i++) {
            float angle = i < 8 ? 96f + i * 9f : 168f;
            float drop = i < 8 ? .54f * (1f - (i + 1) / 8f) : 0f;
            feed(recorder, angle, drop, .04f + i * .008f);
        }
        int recoveredAt = recorder.getFrameCount() - 1;
        assertFalse(recorder.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        assertEquals("丢失期间不能补计", 0,
                recorder.getRepAnalyzer().getGoodReps() + recorder.getRepAnalyzer().getBadReps());
        float dx = .04f + 31 * .008f;
        for (float angle : new float[]{175, 160, 140, 120, 95, 95, 120, 140, 160, 175}) {
            float drop = angle < 150 ? (150f - angle) / 150f : 0f;
            for (int i = 0; i < 3; i++) feed(recorder, angle, drop, dx);
        }
        assertEquals(1, recorder.getRepAnalyzer().getGoodReps());

        for (float angle : new float[]{160, 150, 140, 130, 130, 130}) {
            for (int i = 0; i < 3; i++) feed(recorder, angle, angle < 150 ? .12f : 0f, dx);
        }
        assertNull("恢复后个人校准仍应能保存并即时计数", recorder.calibratePersonal());
        assertEquals(2, recorder.getRepAnalyzer().getGoodReps());
        assertNotNull("同一往返不能重复校准", recorder.calibratePersonal());
        good.set(good.size() - 1, recorder.getRepAnalyzer().getGoodReps());
        for (float angle : new float[]{140, 150, 160, 175, 175}) {
            for (int i = 0; i < 3; i++) feed(recorder, angle, angle < 150 ? .12f : 0f, dx);
        }
        float beforeSecondPan = dx;
        for (int n = 0; n < 26; n++) {
            dx = beforeSecondPan - .16f - n * .006f;
            feed(recorder, 175f, 0f, dx);
        }
        assertEquals("横移不能清空已完成和校准的成绩", 2, recorder.getRepAnalyzer().getGoodReps());
        for (float angle : new float[]{160, 150, 140, 130, 130, 130, 140, 150, 160, 175, 175}) {
            for (int i = 0; i < 3; i++) feed(recorder, angle, angle < 150 ? .12f : 0f, dx);
        }
        assertEquals("横移后个人目标仍然适用", 3, recorder.getRepAnalyzer().getGoodReps());
        assertEquals(0, recorder.getRepAnalyzer().getBadReps());
        recorder.finish(100000);
        assertEquals(3, s.getReps());
        assertEquals(1, s.getCalibrationChanges().size());
        assertTrue(s.hasPoseReplay());
        File file = SessionStore.resolveMedia(context, temporary.getId(), s.getPoseFile());
        PoseLog log = PoseLog.load(file);
        assertNotNull(log);
        assertTrue(log.getFrames().get(recoveredAt).isReliable(PoseFrame.LEFT_SHOULDER));
        assertEquals(good.size(), log.getFrames().size());
        assertEquals(good.size(), log.getDisplayFrames().size());
        ReplayTimeline replay = ReplayTimeline.build(log.getFrames(), s);
        for (int i = 0; i < good.size(); i++) {
            assertEquals("实时/骨架回放有效数，帧 " + i, (int) good.get(i), replay.getGoodRepsAt(i));
            assertEquals("实时/骨架回放不标准数，帧 " + i, (int) bad.get(i), replay.getBadRepsAt(i));
        }
        SessionStore.save(context, s);
        Session loaded = SessionStore.loadAll(context, temporary.getId()).get(0);
        assertEquals(3, loaded.getReps());
        assertEquals(1, loaded.getCalibrationChanges().size());
        assertNotNull(UserStore.getById(context, temporary.getId())
                .getCalibration(ExerciseType.SQUAT, ""));
    }

    @Test public void recorderEnablesCoherentPanOnlyForSquat() {
        WorkoutRecorder squat = new WorkoutRecorder(context, session(ExerciseType.SQUAT), false, null);
        WorkoutRecorder push = new WorkoutRecorder(context, session(ExerciseType.PUSHUP), false, null);
        squat.onPose(squat(0, 168f, 0f, 0f), 720, 1280, 0f);
        push.onPose(squat(0, 168f, 0f, 0f), 720, 1280, 0f);
        for (int n = 1; n <= 22; n++) {
            PoseFrame raw = squat(n * 66L, 168f, 0f, .16f + n * .008f);
            squat.onPose(raw, 720, 1280, 0f);
            push.onPose(raw, 720, 1280, 0f);
        }
        assertFalse(squat.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
        assertTrue("其他动作保留逐点候选", push.getPendingJumps()[PoseFrame.LEFT_SHOULDER]);
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
