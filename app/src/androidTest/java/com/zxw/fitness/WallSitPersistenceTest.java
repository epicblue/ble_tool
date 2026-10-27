package com.zxw.fitness;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.fitness.analysis.WorkoutRecorder;
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

import static org.junit.Assert.*;

/** 新累计字段、正在进行的组和没有累计字段的旧记录兼容。 */
@RunWith(AndroidJUnit4.class)
public class WallSitPersistenceTest {
    private Context context;
    private UserProfile original;
    private UserProfile temporary;

    @Before public void setUp() {
        context = InstrumentationRegistry.getTargetContext();
        original = UserStore.getCurrent(context);
        temporary = UserStore.addUser(context, "靠墙蹲累计测试");
    }

    @After public void tearDown() {
        if (temporary != null) UserStore.delete(context, temporary);
        if (original != null && UserStore.getById(context, original.getId()) != null) {
            UserStore.setCurrent(context, original);
        }
    }

    @Test public void savesTotalSeparatelyAndKeepsLegacyUnknown() {
        Session old = newSession();
        old.setHoldMs(2100);
        SessionStore.save(context, old); // 模拟没有 totalHoldMs 键的旧 JSON

        Session current = newSession();
        WorkoutRecorder recorder = new WorkoutRecorder(context, current, false, null);
        long ts = 0;
        for (int i = 0; i < 50; i++, ts += 66) recorder.processRecorded(wallSit(ts, 0f));
        for (int i = 0; i < 16; i++, ts += 66) recorder.processRecorded(wallSit(ts, .25f));
        for (int i = 0; i < 20; i++, ts += 66) recorder.processRecorded(wallSit(ts, 0f));
        long expectedTotal = recorder.getHoldAnalyzer().getTotalHoldMs();
        long expectedBest = recorder.getHoldAnalyzer().getBestHoldMs();
        assertTrue(expectedTotal > expectedBest && expectedBest > 0);
        recorder.finish(10000); // 第二组仍在进行，累计也必须包含它
        assertEquals(expectedBest, current.getHoldMs());
        assertEquals(expectedTotal, current.getTotalHoldMs());
        SessionStore.save(context, current);

        Session zero = newSession();
        zero.setTotalHoldMs(0);
        SessionStore.save(context, zero);
        Session loadedOld = find(old.getId());
        Session loadedCurrent = find(current.getId());
        Session loadedZero = find(zero.getId());
        assertNotNull(loadedOld);
        assertFalse(loadedOld.hasTotalHoldMs());
        assertEquals(-1, loadedOld.getTotalHoldMs());
        assertEquals(2100, loadedOld.getHoldMs());
        assertTrue(loadedCurrent.hasTotalHoldMs());
        assertEquals(expectedBest, loadedCurrent.getHoldMs());
        assertEquals(expectedTotal, loadedCurrent.getTotalHoldMs());
        assertTrue(loadedZero.hasTotalHoldMs());
        assertEquals(0, loadedZero.getTotalHoldMs());
    }

    private Session newSession() {
        Session session = new Session();
        session.setId(SessionStore.newSessionId());
        session.setUserId(temporary.getId());
        session.setExerciseType(ExerciseType.WALL_SIT);
        session.setStartedAt(1000);
        return session;
    }

    private Session find(long id) {
        for (Session session : SessionStore.loadAll(context, temporary.getId())) {
            if (session.getId() == id) return session;
        }
        return null;
    }

    private static PoseFrame wallSit(long ts, float hipLift) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        float kneeX = .62f, kneeY = .70f, hipX = .44f, hipY = kneeY - hipLift;
        float shoulderY = hipY - .28f;
        int[][] sides = {
                {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                        PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE},
                {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                        PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE}
        };
        for (int[] side : sides) {
            float[] px = {hipX, hipX + .02f, hipX + .02f, hipX, kneeX, kneeX};
            float[] py = {shoulderY, shoulderY + .10f, shoulderY + .17f, hipY, kneeY, kneeY + .20f};
            for (int i = 0; i < side.length; i++) {
                x[side[i]] = px[i]; y[side[i]] = py[i]; c[side[i]] = .9f;
            }
        }
        x[PoseFrame.NOSE] = hipX; y[PoseFrame.NOSE] = shoulderY - .06f; c[PoseFrame.NOSE] = .9f;
        return PoseFrame.of(ts, x, y, z, c);
    }
}
