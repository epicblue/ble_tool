package com.zxw.fitness;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.zxw.R;
import com.zxw.fitness.analysis.VideoPoseReanalysis;
import com.zxw.fitness.camera.VideoPoseReanalyzer;
import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.data.SessionStore;
import com.zxw.fitness.data.UserStore;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.UserProfile;
import com.zxw.fitness.ui.ReplayActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** 真机/模拟器测试：真实 MP4 解码 + 真实 ML Kit；样片无运动人体，不验证识别准确率。 */
@RunWith(AndroidJUnit4.class)
public class VideoReanalysisIntegrationTest {
    private static final class Callback implements VideoPoseReanalyzer.Listener {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        volatile VideoPoseReanalysis result;
        volatile String error;
        @Override public void onProgress(int complete, int total) { calls.incrementAndGet(); }
        @Override public void onCompleted(VideoPoseReanalysis value) {
            result = value;
            calls.incrementAndGet();
            done.countDown();
        }
        @Override public void onError(String message) {
            error = message;
            calls.incrementAndGet();
            done.countDown();
        }
    }

    private static Session session() {
        Session session = new Session();
        session.setExerciseType(ExerciseType.SQUAT);
        session.setPoseFile("old-pose.dat");
        session.setVideoFile("original.mp4");
        session.setReps(12);
        return session;
    }

    private static File copyFixture() throws Exception {
        Context target = InstrumentationRegistry.getTargetContext();
        File video = File.createTempFile("rotated-replay-", ".mp4", target.getCacheDir());
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext()
                .getAssets().open("90rotation.mp4"); FileOutputStream out = new FileOutputStream(video)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        return video;
    }

    private static byte[] read(File file) throws Exception {
        try (InputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static void onMain(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    @Test public void rotatedVideoIsActuallyDecodedAndReanalyzedWithoutChangingSource() throws Exception {
        File video = copyFixture();
        byte[] original = read(video);
        Session saved = session();
        VideoPoseReanalyzer analyzer = new VideoPoseReanalyzer();
        Callback callback = new Callback();
        try {
            onMain(() -> analyzer.start(video, saved, callback));
            assertTrue("视频重识别超时", callback.done.await(120, TimeUnit.SECONDS));
            assertNull(callback.error, callback.error);
            VideoPoseReanalysis result = callback.result;
            assertNotNull(result);
            assertTrue(result.getDurationMs() > 0);
            assertTrue(result.size() > 0);
            assertEquals(result.size(), result.getTimeline().size());
            assertEquals(0, result.getMissingFrames());
            assertEquals(0, result.getRawFrame(0).getTimestampMs());
            // 样片编码为横屏但携带 90° 旋转；检测图应是摆正后的竖屏画面。
            assertTrue("设备的抽帧没有按录像旋转信息摆正，不能可靠叠加骨架",
                    result.getSourceWidth() < result.getSourceHeight());
            assertArrayEquals(original, read(video));
            assertEquals("old-pose.dat", saved.getPoseFile());
            assertEquals("original.mp4", saved.getVideoFile());
            assertEquals(12, saved.getReps());
        } finally {
            onMain(analyzer::shutdown);
            //noinspection ResultOfMethodCallIgnored
            video.delete();
        }
    }

    @Test public void replayScreenDefaultsToNewVideoSkeletonAndCanSwitchToRecorded() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        UserProfile originalUser = UserStore.getCurrent(context);
        UserProfile testUser = UserStore.addUser(context, "视频回放临时测试");
        File fixture = null;
        Session saved = session();
        saved.setId(SessionStore.newSessionId());
        saved.setUserId(testUser.getId());
        saved.setStartedAt(System.currentTimeMillis());
        saved.setDurationMs(196);
        saved.setVideoFile("fixture.mp4");
        saved.setPoseFile("fixture-pose.dat");
        File video = SessionStore.resolveMedia(context, testUser.getId(), saved.getVideoFile());
        File pose = SessionStore.resolveMedia(context, testUser.getId(), saved.getPoseFile());
        Activity activity = null;
        try {
            fixture = copyFixture();
            byte[] originalVideo = read(fixture);
            try (FileOutputStream out = new FileOutputStream(video)) { out.write(originalVideo); }
            PoseLogWriter writer = new PoseLogWriter(pose, 1080, 1920);
            writer.addFrame(PoseFrame.empty(0));
            writer.close();
            assertFalse(writer.hasError());
            byte[] originalPose = read(pose);
            SessionStore.save(context, saved);
            Intent intent = new Intent(context, ReplayActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(ReplayActivity.EXTRA_USER_ID, testUser.getId());
            intent.putExtra(ReplayActivity.EXTRA_SESSION_ID, saved.getId());
            activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
            final Activity screen = activity;
            long deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(120);
            String mode = "";
            while (SystemClock.elapsedRealtime() < deadline) {
                mode = modeText(screen);
                if ("骨架：新滤波".equals(mode) || "重新识别".equals(mode)) break;
                Thread.sleep(200);
            }
            assertEquals("录像应默认显示视频重识别后的新滤波骨架，失败时请检查设备解码/模型",
                    "骨架：新滤波", mode);
            onMain(() -> screen.findViewById(R.id.btnPoseMode).performClick());
            assertEquals("骨架：录制", modeText(screen));
            onMain(() -> screen.findViewById(R.id.btnPoseMode).performClick());
            assertEquals("骨架：新原始", modeText(screen));
            onMain(() -> screen.findViewById(R.id.btnPoseMode).performClick());
            assertEquals("骨架：新滤波", modeText(screen));
            assertArrayEquals(originalVideo, read(video));
            assertArrayEquals(originalPose, read(pose));
            assertEquals(12, SessionStore.loadAll(context, testUser.getId()).get(0).getReps());

            // 同一账号的无视频记录仍进入纯骨架模式，不启动视频重识别。
            onMain(screen::finish);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            activity = null;
            Session skeletonOnly = session();
            skeletonOnly.setId(SessionStore.newSessionId());
            skeletonOnly.setUserId(testUser.getId());
            skeletonOnly.setStartedAt(System.currentTimeMillis());
            skeletonOnly.setDurationMs(196);
            skeletonOnly.setVideoFile(null);
            skeletonOnly.setPoseFile(saved.getPoseFile());
            SessionStore.save(context, skeletonOnly);
            Intent pureIntent = new Intent(context, ReplayActivity.class);
            pureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pureIntent.putExtra(ReplayActivity.EXTRA_USER_ID, testUser.getId());
            pureIntent.putExtra(ReplayActivity.EXTRA_SESSION_ID, skeletonOnly.getId());
            activity = InstrumentationRegistry.getInstrumentation().startActivitySync(pureIntent);
            final Activity pureScreen = activity;
            assertEquals("仅录制骨架", modeText(pureScreen));
            onMain(() -> assertFalse(pureScreen.findViewById(R.id.btnPoseMode).isEnabled()));
            assertArrayEquals(originalPose, read(pose));
        } finally {
            if (activity != null) {
                final Activity screen = activity;
                onMain(screen::finish);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            }
            if (fixture != null) {
                //noinspection ResultOfMethodCallIgnored
                fixture.delete();
            }
            UserStore.delete(context, testUser);
            if (originalUser != null && UserStore.getById(context, originalUser.getId()) != null) {
                UserStore.setCurrent(context, originalUser);
            }
        }
    }

    private static String modeText(Activity screen) throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        onMain(() -> {
            TextView view = screen.findViewById(R.id.btnPoseMode);
            text.set(view == null ? "" : view.getText().toString());
        });
        return text.get();
    }

    @Test public void corruptVideoReportsFailureWithoutChangingSourceOrHistory() throws Exception {
        File video = File.createTempFile("invalid-replay-", ".mp4",
                InstrumentationRegistry.getTargetContext().getCacheDir());
        byte[] invalid = {1, 2, 3, 4, 5, 6};
        try (FileOutputStream out = new FileOutputStream(video)) { out.write(invalid); }
        VideoPoseReanalyzer analyzer = new VideoPoseReanalyzer();
        Session saved = session();
        Callback callback = new Callback();
        try {
            onMain(() -> analyzer.start(video, saved, callback));
            assertTrue(callback.done.await(30, TimeUnit.SECONDS));
            assertNotNull(callback.error);
            assertNull(callback.result);
            assertArrayEquals(invalid, read(video));
            assertEquals("old-pose.dat", saved.getPoseFile());
            assertEquals(12, saved.getReps());
        } finally {
            onMain(analyzer::shutdown);
            //noinspection ResultOfMethodCallIgnored
            video.delete();
        }
    }

    @Test public void cancelledOldJobCannotPublishIntoNewJob() throws Exception {
        File video = copyFixture();
        VideoPoseReanalyzer analyzer = new VideoPoseReanalyzer();
        Callback old = new Callback();
        Callback replacement = new Callback();
        try {
            onMain(() -> {
                analyzer.start(video, session(), old);
                analyzer.cancel();
                analyzer.start(video, session(), replacement);
            });
            assertTrue("新任务超时", replacement.done.await(120, TimeUnit.SECONDS));
            assertNull(replacement.error, replacement.error);
            assertNotNull(replacement.result);
            // 排空主线程队列，确认之前排队的旧任务进度也被丢弃。
            onMain(() -> { });
            assertEquals(0, old.calls.get());
        } finally {
            onMain(analyzer::shutdown);
            //noinspection ResultOfMethodCallIgnored
            video.delete();
        }
    }
}
