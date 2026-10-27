package com.zxw.fitness;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.R;
import com.zxw.fitness.analysis.AlgorithmVersion;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.data.RecordedTimeline.Writer;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** 设备检查：同版记录有视频时，回放入口先显示录制结果而非自动重识别。 */
@RunWith(AndroidJUnit4.class)
public class ReplayCacheIntegrationTest {
    private Context context;
    private UserProfile original, temporary;
    private Activity activity;

    @Before public void setUp() {
        context = InstrumentationRegistry.getTargetContext();
        original = UserStore.getCurrent(context);
        temporary = UserStore.addUser(context, "回放缓存测试");
    }

    @After public void tearDown() {
        if (activity != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.finish());
        if (temporary != null) UserStore.delete(context, temporary);
        if (original != null && UserStore.getById(context, original.getId()) != null) {
            UserStore.setCurrent(context, original);
        }
    }

    @Test public void matchingVideoRecordStartsWithSavedResultsAndAllowsManualReanalysis() throws Exception {
        Session s = new Session();
        s.setId(SessionStore.newSessionId()); s.setUserId(temporary.getId());
        s.setExerciseType(ExerciseType.SQUAT); s.setAlgorithmVersion(AlgorithmVersion.CURRENT);
        File dir = SessionStore.mediaDir(context, s.getUserId());
        File pose = new File(dir, s.getId() + "_pose.dat");
        File cache = new File(dir, s.getId() + "_timeline.dat");
        File video = new File(dir, s.getId() + ".mp4");
        s.setPoseFile(pose.getName()); s.setTimelineFile(cache.getName()); s.setVideoFile(video.getName());
        PoseLogWriter pw = new PoseLogWriter(pose, 720, 1280);
        Writer writer = new Writer(cache, s);
        for (int i = 0; i < 2; i++) {
            PoseFrame frame = PoseFrame.empty(i * 100L);
            pw.addFrame(frame);
            writer.addFrame(frame, new ReplayTimeline.FrameState(0, 0, "录制状态", 0, 0, 0));
        }
        pw.close();
        assertTrue(writer.finish(pose, s, 2));
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("90rotation.mp4"); FileOutputStream out = new FileOutputStream(video)) {
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
        SessionStore.save(context, s);
        Intent intent = new Intent(context, ReplayActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(ReplayActivity.EXTRA_USER_ID, temporary.getId());
        intent.putExtra(ReplayActivity.EXTRA_SESSION_ID, s.getId());
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        AtomicReference<String> info = new AtomicReference<>("");
        AtomicReference<String> button = new AtomicReference<>("");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            info.set(((TextView) activity.findViewById(R.id.tvReplayInfo)).getText().toString());
            button.set(((TextView) activity.findViewById(R.id.btnPoseMode)).getText().toString());
        });
        assertTrue(info.get(), info.get().contains("未重新计算"));
        assertEquals("重新识别", button.get());
    }
}
