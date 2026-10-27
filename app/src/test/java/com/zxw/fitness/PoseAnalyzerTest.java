package com.zxw.fitness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.zxw.fitness.analysis.PushUpCounter;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 用合成的关键点序列验证俯卧撑计数与倒立计时的判定阈值。
 *
 * <p>这些测试不依赖 Android 运行时，可以直接在本地 JVM 上运行
 * （{@code ./gradlew :app:testDebugUnitTest}）。</p>
 */
public class PoseAnalyzerTest {

    private static final int FPS_STEP_MS = 66;

    // ---------------- 合成关键点 ----------------

    private static PoseFrame blankFrame(long ts) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        return PoseFrame.of(ts, x, y, z, c);
    }

    private static void put(float[] x, float[] y, float[] c, int index,
                            float px, float py, float conf) {
        x[index] = px;
        y[index] = py;
        c[index] = conf;
    }

    /**
     * 侧视角度的俯卧撑：肩在手腕上方，肘角由 target 决定（肘在肩腕连线的垂直平分线上）。
     */
    private static PoseFrame pushUpFrame(long ts, float targetElbowDeg, float sag, boolean kneel) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];

        float sx = 0.40f;
        float sy = 0.55f;
        float wx = 0.40f;
        float wy = 0.75f;
        float d = wy - sy;
        float h = (float) ((d / 2.0) / Math.tan(Math.toRadians(targetElbowDeg) / 2.0));
        float ex = sx + h;
        float ey = (sy + wy) / 2f;

        float hipY = 0.55f + sag;
        float ankleY = 0.55f;
        float kneeX = kneel ? 0.68f : 0.70f;
        float kneeY = kneel ? 0.62f : 0.55f;
        float ankleX = kneel ? 0.78f : 0.80f;

        put(x, y, c, PoseFrame.NOSE, 0.35f, 0.52f, 0.9f);
        int[] left = {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE};
        int[] right = {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
        float[][] points = {
                {sx, sy}, {ex, ey}, {wx, wy}, {0.60f, hipY}, {kneeX, kneeY}, {ankleX, ankleY}
        };
        for (int i = 0; i < points.length; i++) {
            put(x, y, c, left[i], points[i][0], points[i][1], 0.9f);
            put(x, y, c, right[i], points[i][0], points[i][1], 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    /** 站立的人（躯干竖直、双手垂在体侧）：不应被计为俯卧撑。 */
    private static PoseFrame standingFrame(long ts) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        int[] left = {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE};
        int[] right = {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
        float[][] points = {
                {0.45f, 0.30f}, {0.45f, 0.45f}, {0.45f, 0.55f},
                {0.47f, 0.50f}, {0.47f, 0.70f}, {0.47f, 0.85f}
        };
        for (int i = 0; i < points.length; i++) {
            put(x, y, c, left[i], points[i][0], points[i][1], 0.9f);
            put(x, y, c, right[i], points[i][0], points[i][1], 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    /**
     * 倒立：髋在上、肩在下、脚在最高处；tilt 控制躯干倾斜角度。
     */
    private static PoseFrame handstandFrame(long ts, float tiltDeg, boolean inverted) {
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];

        float hipX = 0.5f;
        float hipY = inverted ? 0.45f : 0.45f;
        float shoulderY = inverted ? 0.62f : 0.30f;
        float ankleY = inverted ? 0.18f : 0.75f;
        float dx = (float) (Math.tan(Math.toRadians(tiltDeg)) * (shoulderY - hipY));

        put(x, y, c, PoseFrame.NOSE, hipX + dx, shoulderY + 0.03f, 0.9f);
        int[] left = {PoseFrame.LEFT_SHOULDER, PoseFrame.LEFT_ELBOW, PoseFrame.LEFT_WRIST,
                PoseFrame.LEFT_HIP, PoseFrame.LEFT_KNEE, PoseFrame.LEFT_ANKLE};
        int[] right = {PoseFrame.RIGHT_SHOULDER, PoseFrame.RIGHT_ELBOW, PoseFrame.RIGHT_WRIST,
                PoseFrame.RIGHT_HIP, PoseFrame.RIGHT_KNEE, PoseFrame.RIGHT_ANKLE};
        float[][] points = {
                {hipX + dx, shoulderY},
                {hipX + dx + 0.03f, shoulderY + 0.06f},
                {hipX + dx + 0.03f, 0.76f},
                {hipX, hipY},
                {hipX, (hipY + ankleY) / 2f},
                {hipX, ankleY}
        };
        for (int i = 0; i < points.length; i++) {
            put(x, y, c, left[i], points[i][0], points[i][1], 0.9f);
            put(x, y, c, right[i], points[i][0], points[i][1], 0.9f);
        }
        return PoseFrame.of(ts, x, y, z, c);
    }

    /** 一次完整动作的角度序列：下放 + 撑起。 */
    private static float[] repProfile(float bottomAngle) {
        int nDown = 8;
        int nUp = 8;
        float[] angles = new float[nDown + nUp];
        for (int i = 0; i < nDown; i++) {
            angles[i] = 168f + (bottomAngle - 168f) * (i + 1) / nDown;
        }
        for (int i = 0; i < nUp; i++) {
            angles[nDown + i] = bottomAngle + (168f - bottomAngle) * (i + 1) / nUp;
        }
        return angles;
    }

    private static long feedRep(PushUpCounter counter, long ts, float bottomAngle,
                                float sag, boolean kneel) {
        for (float angle : repProfile(bottomAngle)) {
            counter.process(pushUpFrame(ts, angle, sag, kneel));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 3; i++) {
            counter.process(pushUpFrame(ts, 168f, sag, kneel));
            ts += FPS_STEP_MS;
        }
        return ts;
    }

    // ---------------- 测试 ----------------

    @Test
    public void countsGoodPushUps() {
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long ts = 0;
        for (int i = 0; i < 5; i++) {
            ts = feedRep(counter, ts, 90f, 0f, false);
        }
        assertEquals(5, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        assertEquals("5 次", counter.getScoreText());
    }

    @Test
    public void flagsShallowAndBadPosture() {
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long ts = 0;
        // 幅度不足（最低只到 110 度）
        ts = feedRep(counter, ts, 110f, 0f, false);
        // 塌腰
        ts = feedRep(counter, ts, 90f, 0.08f, false);
        // 跪姿
        feedRep(counter, ts, 90f, 0f, true);

        assertEquals(0, counter.getGoodReps());
        assertEquals(3, counter.getBadReps());
        assertEquals(3, counter.getFormErrors());
    }

    @Test
    public void doesNotCountWhileStanding() {
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long ts = 0;
        for (int i = 0; i < 30; i++) {
            counter.process(standingFrame(ts));
            ts += FPS_STEP_MS;
        }
        assertEquals(0, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        assertFalse(counter.hasResult());
    }

    @Test
    public void ignoresShortJitter() {
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long ts = 0;
        counter.process(pushUpFrame(ts, 160f, 0f, false));
        ts += FPS_STEP_MS;
        counter.process(pushUpFrame(ts, 95f, 0f, false)); // 只下降一帧
        ts += 100;
        // 迅速弹起：整次往返不到 350ms，应被当作抖动忽略
        for (int i = 0; i < 3; i++) {
            counter.process(pushUpFrame(ts, 168f, 0f, false));
            ts += 50;
        }
        assertEquals(0, counter.getGoodReps() + counter.getBadReps());
    }

    @Test
    public void timesHandstandHoldAcrossShortWobble() {
        com.zxw.fitness.analysis.HandstandTimer timer =
                new com.zxw.fitness.analysis.HandstandTimer(ExerciseType.HANDSTAND, null);
        long ts = 0;
        for (int i = 0; i < 10; i++) {
            timer.process(handstandFrame(ts, 0f, false));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 45; i++) { // 3 秒
            timer.process(handstandFrame(ts, 3f, true));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 4; i++) { // 0.26 秒晃动，在宽限期内
            timer.process(handstandFrame(ts, 0f, false));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 30; i++) { // 再坚持 2 秒
            timer.process(handstandFrame(ts, 3f, true));
            ts += FPS_STEP_MS;
        }
        long best = timer.getBestHoldMs();
        assertTrue("best=" + best, best >= 4800 && best <= 5600);
        assertEquals(1, timer.getAttempts());
    }

    @Test
    public void rejectsTiltedBody() {
        com.zxw.fitness.analysis.HandstandTimer timer =
                new com.zxw.fitness.analysis.HandstandTimer(ExerciseType.HANDSTAND, null);
        long ts = 0;
        for (int i = 0; i < 60; i++) {
            timer.process(handstandFrame(ts, 45f, true));
            ts += FPS_STEP_MS;
        }
        assertEquals(0, timer.getBestHoldMs());
        assertEquals(0, timer.getAttempts());
    }

    @Test
    public void buildsReplayTimeline() {
        List<PoseFrame> frames = new ArrayList<>();
        long ts = 0;
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        for (int rep = 0; rep < 3; rep++) {
            for (float angle : repProfile(90f)) {
                frames.add(pushUpFrame(ts, angle, 0f, false));
                ts += FPS_STEP_MS;
            }
            for (int i = 0; i < 3; i++) {
                frames.add(pushUpFrame(ts, 168f, 0f, false));
                ts += FPS_STEP_MS;
            }
        }
        // 逐帧喂给计数器和 timeline，确认两者结果一致
        int expected = 0;
        for (PoseFrame frame : frames) {
            counter.process(frame);
            expected = counter.getGoodReps();
        }
        ReplayTimeline timeline = ReplayTimeline.build(frames, ExerciseType.PUSHUP, "");
        int last = timeline.size() - 1;
        assertEquals(expected, timeline.getGoodRepsAt(last));
        assertEquals(3, (int) timeline.getScoreAt(last));
    }

    @Test
    public void poseLogRoundTrip() throws Exception {
        File file = File.createTempFile("pose", ".dat");
        //noinspection ResultOfMethodCallIgnored
        file.deleteOnExit();
        PoseLogWriter writer = new PoseLogWriter(file, 720, 1280);
        List<PoseFrame> originals = new ArrayList<>();
        long ts = 1000;
        for (int i = 0; i < 10; i++) {
            PoseFrame frame = pushUpFrame(ts, 90f + i * 3f, 0f, false);
            writer.addFrame(frame);
            originals.add(frame);
            ts += 66;
        }
        writer.close();
        PoseLog log = PoseLog.load(file);
        assertNotNull(log);
        assertEquals(originals.size(), log.getFrames().size());
        assertEquals(720, log.getViewWidth());
        assertEquals(1280, log.getViewHeight());
        PoseFrame restored = log.getFrames().get(5);
        PoseFrame source = originals.get(5);
        assertEquals(source.getX(PoseFrame.LEFT_SHOULDER),
                restored.getX(PoseFrame.LEFT_SHOULDER), 0.0001f);
        assertEquals(source.getY(PoseFrame.RIGHT_ANKLE),
                restored.getY(PoseFrame.RIGHT_ANKLE), 0.0001f);
        assertTrue(log.getDurationMs() > 0);
    }

    @Test
    public void exerciseTypesAreStable() {
        // 存储 key 不能随意变动，否则历史记录会失效
        assertEquals(ExerciseType.HANDSTAND, ExerciseType.fromKey("handstand"));
        assertEquals(ExerciseType.WALL_HANDSTAND, ExerciseType.fromKey("wall_handstand"));
        assertEquals(ExerciseType.CHAIR_PUSHUP, ExerciseType.fromKey("chair_pushup"));
        assertEquals(ExerciseType.PUSHUP, ExerciseType.fromKey("pushup"));
        assertEquals(ExerciseType.BAD_PUSHUP, ExerciseType.fromKey("bad_pushup"));
        assertEquals(ExerciseType.PUSHUP, ExerciseType.fromKey("unknown"));
        assertTrue(ExerciseType.HANDSTAND.isTimerExercise());
        assertFalse(ExerciseType.PUSHUP.isTimerExercise());
        assertTrue(ExerciseType.WALL_HANDSTAND.isWallExercise());
    }
}
