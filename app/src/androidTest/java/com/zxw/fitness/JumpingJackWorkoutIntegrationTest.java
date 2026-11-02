package com.zxw.fitness;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.R;
import com.zxw.fitness.analysis.AlgorithmVersion;
import com.zxw.fitness.analysis.JumpingJackCounter;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.WorkoutRecorder;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.RecordedTimeline;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;
import com.zxw.fitness.ui.ReplayActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** 真实记录器、账号/JSON、缓存和回放入口的设备测试；帧为合成数据，不测试真人识别。 */
@RunWith(AndroidJUnit4.class)
public class JumpingJackWorkoutIntegrationTest {
    private Context context;
    private UserProfile original, child;
    private Activity screen;
    private long time;
    private final List<Integer> live = new ArrayList<>();

    @Before public void setup() {
        context = InstrumentationRegistry.getTargetContext();
        original = UserStore.getCurrent(context);
        child = UserStore.addUser(context, "开合跳测试");
        time = 1000;
    }

    @After public void cleanup() {
        if (screen != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> screen.finish());
        if (child != null) UserStore.delete(context, child);
        if (original != null && UserStore.getById(context, original.getId()) != null) {
            UserStore.setCurrent(context, original);
        }
    }

    private Session newSession() {
        Session session = new Session(); session.setId(SessionStore.newSessionId());
        session.setUserId(child.getId()); session.setExerciseType(ExerciseType.JUMPING_JACK);
        session.setStartedAt(1000); return session;
    }

    private void hold(WorkoutRecorder recorder, float arms, float feet, int count) {
        for (int i = 0; i < count; i++, time += 100) {
            recorder.onPose(jack(time, arms, feet), 720, 1280, 0);
            live.add(recorder.getRepAnalyzer().getGoodReps());
        }
    }

    private void openPersonal(WorkoutRecorder recorder) {
        for (float p : new float[]{.1f, .2f, .3f, .4f}) hold(recorder, p, p * .75f, 3);
        hold(recorder, .4f, .3f, 4);
    }

    private void closePersonal(WorkoutRecorder recorder) {
        for (float p : new float[]{.3f, .2f, .1f, 0f}) hold(recorder, p, p * .75f, 3);
        hold(recorder, 0, 0, 5);
    }

    @Test public void calibrationJsonAndBothReplayTimelinesRetainFrameByFrameCounts() throws Exception {
        Session session = newSession();
        WorkoutRecorder recorder = new WorkoutRecorder(context, session, true, null);
        assertTrue(recorder.getRepAnalyzer() instanceof JumpingJackCounter);
        hold(recorder, 0, 0, 8); openPersonal(recorder);
        assertNull("稳定张开后校准", recorder.calibratePersonal());
        live.set(live.size() - 1, recorder.getRepAnalyzer().getGoodReps());
        assertNotNull("未合拢时不可重复校准", recorder.calibratePersonal());
        UserProfile loadedUser = UserStore.getById(context, child.getId());
        PersonalCalibration saved = loadedUser.getCalibration(ExerciseType.JUMPING_JACK, "");
        assertNotNull(saved);
        assertTrue(saved.isValid());
        assertNull(loadedUser.getCalibration(ExerciseType.SQUAT, ""));
        closePersonal(recorder); openPersonal(recorder); closePersonal(recorder);
        recorder.finish(100000);
        assertEquals(2, session.getReps()); assertEquals(0, session.getBadReps());
        assertEquals(AlgorithmVersion.JUMPING_JACK_CURRENT, session.getAlgorithmVersion());
        assertEquals(1, session.getCalibrationChanges().size());
        SessionStore.save(context, session);
        Session restored = SessionStore.loadByType(context, child.getId(), ExerciseType.JUMPING_JACK).get(0);
        assertEquals(ExerciseType.JUMPING_JACK, restored.getExerciseType());
        assertEquals(AlgorithmVersion.JUMPING_JACK_CURRENT, restored.getAlgorithmVersion());
        assertEquals(1, restored.getCalibrationChanges().size());
        assertEquals(saved.getTargetDepth(), restored.getCalibrationChanges().get(0).calibration.getTargetDepth(), .00001f);
        assertEquals(saved.getMinMovement(), restored.getCalibrationChanges().get(0).calibration.getMinMovement(), .00001f);
        File pose = SessionStore.resolveMedia(context, child.getId(), restored.getPoseFile());
        File cache = SessionStore.resolveMedia(context, child.getId(), restored.getTimelineFile());
        PoseLog log = PoseLog.load(pose);
        ReplayTimeline recorded = RecordedTimeline.load(cache, pose, log, restored);
        assertNotNull(recorded);
        ReplayTimeline rebuilt = ReplayTimeline.build(log.getFrames(), restored);
        assertEquals(live.size(), recorded.size());
        for (int i = 0; i < live.size(); i++) {
            assertEquals("缓存帧 " + i, (int) live.get(i), recorded.getGoodRepsAt(i));
            assertEquals("骨架重算帧 " + i, (int) live.get(i), rebuilt.getGoodRepsAt(i));
        }
        ReplayTimeline video = ReplayTimeline.buildForVideo(log.getFrames(), restored);
        assertEquals("视频重识别不会伪造组中校准", 0, video.getGoodRepsAt(video.size() - 1));
        Session next = newSession(); next.setInitialCalibration(saved);
        WorkoutRecorder nextRecorder = new WorkoutRecorder(context, next, false, null);
        hold(nextRecorder, 0, 0, 8); openPersonal(nextRecorder); closePersonal(nextRecorder);
        nextRecorder.finish(100000);
        assertEquals("下一组自动采用个人目标", 1, next.getReps());
        assertTrue(UserStore.clearCalibration(context, child.getId(), ExerciseType.JUMPING_JACK, ""));
        assertNull(UserStore.getById(context, child.getId()).getCalibration(ExerciseType.JUMPING_JACK, ""));
        assertEquals("清除个人设置不改写历史快照", 1, restored.getCalibrationChanges().size());
        SessionStore.delete(context, restored);
        assertFalse(pose.exists()); assertFalse(cache.exists());
    }

    @Test public void cacheBackedVideoAndSkeletonRecordsOpenInReplay() throws Exception {
        Session session = newSession();
        WorkoutRecorder recorder = new WorkoutRecorder(context, session, true, null);
        hold(recorder, 0, 0, 8);
        for (float p : new float[]{.2f, .4f, .6f, .8f, 1f, 1f, .8f, .6f, .4f, .2f, 0f}) {
            hold(recorder, p, p, 3);
        }
        hold(recorder, 0, 0, 6); recorder.finish(100000);
        assertEquals(1, session.getReps());
        SessionStore.save(context, session);
        screen = openReplay(session);
        AtomicReference<String> info = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals("开合跳", ((TextView) screen.findViewById(R.id.tvTitle)).getText().toString());
            info.set(((TextView) screen.findViewById(R.id.tvReplayInfo)).getText().toString());
            assertEquals("仅录制骨架", ((TextView) screen.findViewById(R.id.btnPoseMode)).getText().toString());
        });
        assertTrue(info.get(), info.get().contains("未重新计算"));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> screen.finish()); screen = null;

        // 该短片没有人体，仅用于验证视频分支与缓存入口，不能验证人体或时空对齐。
        File video = SessionStore.resolveMedia(context, child.getId(), session.getId() + ".mp4");
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("90rotation.mp4");
             FileOutputStream out = new FileOutputStream(video)) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        session.setVideoFile(video.getName()); SessionStore.save(context, session);
        screen = openReplay(session);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            assertEquals("开合跳", ((TextView) screen.findViewById(R.id.tvTitle)).getText().toString());
            assertTrue(((TextView) screen.findViewById(R.id.tvReplayInfo)).getText().toString().contains("未重新计算"));
            assertEquals("重新识别", ((TextView) screen.findViewById(R.id.btnPoseMode)).getText().toString());
        });
    }

    private Activity openReplay(Session session) {
        Intent intent = new Intent(context, ReplayActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(ReplayActivity.EXTRA_USER_ID, child.getId());
        intent.putExtra(ReplayActivity.EXTRA_SESSION_ID, session.getId());
        return InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
    }

    private static PoseFrame jack(long t, float arms, float feet) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT], y = x.clone(), z = x.clone(), c = x.clone();
        x[PoseFrame.LEFT_SHOULDER] = .43f; y[PoseFrame.LEFT_SHOULDER] = .35f;
        x[PoseFrame.RIGHT_SHOULDER] = .57f; y[PoseFrame.RIGHT_SHOULDER] = .35f;
        x[PoseFrame.LEFT_HIP] = .46f; y[PoseFrame.LEFT_HIP] = .55f;
        x[PoseFrame.RIGHT_HIP] = .54f; y[PoseFrame.RIGHT_HIP] = .55f;
        double angle = Math.toRadians(arms * 165);
        float dx = (float) Math.sin(angle) * .22f, dy = (float) Math.cos(angle) * .22f;
        x[PoseFrame.LEFT_WRIST] = .43f - dx; y[PoseFrame.LEFT_WRIST] = .35f + dy;
        x[PoseFrame.RIGHT_WRIST] = .57f + dx; y[PoseFrame.RIGHT_WRIST] = .35f + dy;
        x[PoseFrame.LEFT_ANKLE] = .47f - .13f * feet; y[PoseFrame.LEFT_ANKLE] = .95f;
        x[PoseFrame.RIGHT_ANKLE] = .53f + .13f * feet; y[PoseFrame.RIGHT_ANKLE] = .95f;
        for (int i : new int[]{PoseFrame.LEFT_SHOULDER, PoseFrame.RIGHT_SHOULDER,
                PoseFrame.LEFT_HIP, PoseFrame.RIGHT_HIP, PoseFrame.LEFT_WRIST,
                PoseFrame.RIGHT_WRIST, PoseFrame.LEFT_ANKLE, PoseFrame.RIGHT_ANKLE}) c[i] = .9f;
        return PoseFrame.of(t, x, y, z, c);
    }
}
