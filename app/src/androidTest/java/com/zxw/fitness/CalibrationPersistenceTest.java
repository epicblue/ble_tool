package com.zxw.fitness;

import android.content.Context;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.*;

/** 真机测试 SharedPreferences/JSON：只创建临时账号，结束时不动已有账号的数据。 */
@RunWith(AndroidJUnit4.class)
public class CalibrationPersistenceTest {
    private Context context;
    private UserProfile original;
    private UserProfile a;
    private UserProfile b;

    @Before public void setUp() {
        context = InstrumentationRegistry.getTargetContext();
        original = UserStore.getCurrent(context);
        a = UserStore.addUser(context, "校准测试 A");
        b = UserStore.addUser(context, "校准测试 B");
    }

    @After public void tearDown() {
        if (a != null) UserStore.delete(context, a);
        if (b != null) UserStore.delete(context, b);
        if (original != null && UserStore.getById(context, original.getId()) != null) {
            UserStore.setCurrent(context, original);
        }
    }

    @Test public void profilesAndHistoryKeepIndependentCalibrationSnapshots() {
        PersonalCalibration normal = new PersonalCalibration(ExerciseType.PUSHUP, "",
                .68f, 115f, .10f, .13f, 150f, 0f, false, false, 140f);
        PersonalCalibration hands = new PersonalCalibration(ExerciseType.CHAIR_PUSHUP, "hands",
                .5f, 130f, .2f, .2f, 140f, 0f, true, true);
        PersonalCalibration feet = new PersonalCalibration(ExerciseType.CHAIR_PUSHUP, "feet",
                .8f, 110f, .3f, .13f, 150f, 0f, false, false);
        PersonalCalibration replacement = new PersonalCalibration(ExerciseType.PUSHUP, "",
                .9f, 105f, .2f, .13f, 150f, 0f, false, false);
        assertTrue(UserStore.saveCalibration(context, a.getId(), normal));
        assertTrue(UserStore.saveCalibration(context, a.getId(), hands));
        assertTrue(UserStore.saveCalibration(context, a.getId(), feet));
        assertNull(UserStore.getById(context, b.getId()).getCalibration(ExerciseType.PUSHUP, ""));
        UserStore.rename(context, a, "校准测试 A 重命名");
        UserProfile loaded = UserStore.getById(context, a.getId());
        assertEquals(3, loaded.getCalibrations().size());
        assertEquals(115f, loaded.getCalibration(ExerciseType.PUSHUP, "").getCapturedAngle(), .01f);
        assertEquals(140f, loaded.getCalibration(ExerciseType.PUSHUP, "").getStartingAngle(), .01f);
        assertTrue(loaded.getCalibration(ExerciseType.CHAIR_PUSHUP, "hands").isKneelingAllowed());
        assertEquals(110f, loaded.getCalibration(ExerciseType.CHAIR_PUSHUP, "feet").getCapturedAngle(), .01f);

        Session session = new Session();
        session.setId(SessionStore.newSessionId());
        session.setUserId(a.getId());
        session.setExerciseType(ExerciseType.PUSHUP);
        session.setInitialCalibration(normal);
        session.addCalibrationChange(7, replacement);
        SessionStore.save(context, session);
        assertTrue(UserStore.saveCalibration(context, a.getId(), replacement));
        assertTrue(UserStore.clearCalibration(context, a.getId(), ExerciseType.PUSHUP, ""));
        assertNull(UserStore.getById(context, a.getId()).getCalibration(ExerciseType.PUSHUP, ""));
        assertNotNull(UserStore.getById(context, a.getId()).getCalibration(ExerciseType.CHAIR_PUSHUP, "hands"));
        List<Session> history = SessionStore.loadAll(context, a.getId());
        assertEquals(1, history.size());
        assertEquals(.68f, history.get(0).getInitialCalibration().getTargetDepth(), .001f);
        assertEquals(7, history.get(0).getCalibrationChanges().get(0).frameIndex);
        assertEquals(.9f, history.get(0).getCalibrationChanges().get(0).calibration.getTargetDepth(), .001f);
        assertTrue(SessionStore.loadAll(context, b.getId()).isEmpty());
    }
}
