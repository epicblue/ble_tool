package com.zxw.fitness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.zxw.fitness.analysis.HandstandTimer;
import com.zxw.fitness.analysis.PlankTimer;
import com.zxw.fitness.analysis.PullUpCounter;
import com.zxw.fitness.analysis.RunStepCounter;
import com.zxw.fitness.analysis.PushUpCounter;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.SquatCounter;
import com.zxw.fitness.analysis.WallSitTimer;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.BodyMetrics;
import com.zxw.fitness.pose.PoseSmoother;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.zxw.fitness.SyntheticPoses.FPS_STEP_MS;
import static com.zxw.fitness.SyntheticPoses.handstand;
import static com.zxw.fitness.SyntheticPoses.plank;
import static com.zxw.fitness.SyntheticPoses.pullUp;
import static com.zxw.fitness.SyntheticPoses.pushUp;
import static com.zxw.fitness.SyntheticPoses.repProfile;
import static com.zxw.fitness.SyntheticPoses.running;
import static com.zxw.fitness.SyntheticPoses.squat;
import static com.zxw.fitness.SyntheticPoses.standing;
import static com.zxw.fitness.SyntheticPoses.wallSit;

/**
 * 判定逻辑单元测试（纯 JVM，可直接 {@code gradlew :app:testDebugUnitTest}）。
 *
 * <p>用合成关键点验证：俯卧撑计数与不标准识别、深蹲、引体向上、平板支撑、
 * 倒立计时、滤波抗抖动、身体度量与时间轴/文件读写。</p>
 */
public class PoseAnalyzerTest {

    private static long feedPushUpRep(PushUpCounter counter, long ts, float bottomAngle,
                                      float sag, boolean kneel) {
        for (float angle : repProfile(bottomAngle, 168f, 8)) {
            counter.process(pushUp(ts, angle, sag, kneel));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 3; i++) {
            counter.process(pushUp(ts, 168f, sag, kneel));
            ts += FPS_STEP_MS;
        }
        return ts;
    }

    // ---------------- 俯卧撑 ----------------

    @Test
    public void countsGoodPushUps() {
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long ts = 0;
        for (int i = 0; i < 5; i++) {
            ts = feedPushUpRep(counter, ts, 90f, 0f, false);
        }
        assertEquals(5, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
        assertEquals("5 次", counter.getScoreText());
    }

    @Test
    public void flagsShallowSagAndKneeling() {
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long ts = 0;
        ts = feedPushUpRep(counter, ts, 110f, 0f, false);   // 幅度不足
        ts = feedPushUpRep(counter, ts, 90f, 0.10f, false); // 塌腰
        feedPushUpRep(counter, ts, 90f, 0f, true);          // 跪姿

        assertEquals(0, counter.getGoodReps());
        assertEquals(3, counter.getBadReps());
        assertEquals(3, counter.getFormErrors());
    }

    @Test
    public void doesNotCountWhileStanding() {
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long ts = 0;
        for (int i = 0; i < 30; i++) {
            counter.process(standing(ts));
            ts += FPS_STEP_MS;
        }
        assertEquals(0, counter.getGoodReps() + counter.getBadReps());
        assertFalse(counter.hasResult());
    }

    @Test
    public void ignoresJitter() {
        PushUpCounter counter = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        long ts = 0;
        counter.process(pushUp(ts, 160f, 0f, false));
        ts += FPS_STEP_MS;
        counter.process(pushUp(ts, 95f, 0f, false));
        ts += 100;
        for (int i = 0; i < 3; i++) {
            counter.process(pushUp(ts, 168f, 0f, false));
            ts += 50;
        }
        assertEquals(0, counter.getGoodReps() + counter.getBadReps());
    }

    @Test
    public void frontViewUsesWristDropInsteadOfAngle() {
        // 折凳"手撑凳"：斜面行程小，用较浅的角度也应判为有效（阈值放宽）
        PushUpCounter chair = new PushUpCounter(ExerciseType.CHAIR_PUSHUP, "hands", null);
        long ts = 0;
        for (float angle : repProfile(104f, 168f, 8)) {
            chair.process(pushUp(ts, angle, 0f, false));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 3; i++) {
            chair.process(pushUp(ts, 168f, 0f, false));
            ts += FPS_STEP_MS;
        }
        assertEquals(1, chair.getGoodReps());

        // 同一角度下，普通俯卧撑应判为"幅度不足"
        PushUpCounter normal = new PushUpCounter(ExerciseType.PUSHUP, "", null);
        ts = 0;
        for (float angle : repProfile(104f, 168f, 8)) {
            normal.process(pushUp(ts, angle, 0f, false));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 3; i++) {
            normal.process(pushUp(ts, 168f, 0f, false));
            ts += FPS_STEP_MS;
        }
        assertEquals(0, normal.getGoodReps());
        assertEquals(1, normal.getBadReps());
    }

    // ---------------- 深蹲 ----------------

    @Test
    public void countsSquats() {
        SquatCounter counter = new SquatCounter(null);
        long ts = 0;
        for (int rep = 0; rep < 3; rep++) {
            // 站立 → 下蹲 → 站起（每阶段 2 帧，约 1.3s 一次）
            for (float knee : new float[]{175, 160, 140, 120, 95, 95, 120, 140, 160, 175}) {
                float drop = knee < 150 ? (150 - knee) / 150f : 0f;
                for (int i = 0; i < 2; i++) {
                    counter.process(squat(ts, knee, drop));
                    ts += FPS_STEP_MS;
                }
            }
        }
        assertEquals(3, counter.getGoodReps());
        assertEquals(0, counter.getBadReps());
    }

    @Test
    public void flagsShallowSquat() {
        SquatCounter counter = new SquatCounter(null);
        long ts = 0;
        for (float knee : new float[]{175, 160, 140, 130, 130, 140, 160, 175}) {
            for (int i = 0; i < 2; i++) {
                counter.process(squat(ts, knee, knee < 150 ? 0.2f : 0f));
                ts += FPS_STEP_MS;
            }
        }
        assertEquals(0, counter.getGoodReps());
        assertEquals(1, counter.getBadReps());
    }

    @Test
    public void kneeWiggleIsNotCounted() {
        SquatCounter counter = new SquatCounter(null);
        long ts = 0;
        // 膝盖动但髋部没下去
        for (float knee : new float[]{175, 150, 130, 130, 150, 175}) {
            for (int i = 0; i < 2; i++) {
                counter.process(squat(ts, knee, 0f));
                ts += FPS_STEP_MS;
            }
        }
        assertEquals(0, counter.getGoodReps() + counter.getBadReps());
    }

    // ---------------- 引体向上 ----------------

    @Test
    public void countsPullUps() {
        PullUpCounter counter = new PullUpCounter(null);
        long ts = 0;
        // 悬垂 3 帧建立基准，然后拉起（肘角 80，身体上升 0.8）再放下
        for (int i = 0; i < 4; i++) {
            counter.process(pullUp(ts, 172f, 0f));
            ts += FPS_STEP_MS;
        }
        for (int rep = 0; rep < 2; rep++) {
            float[][] phases = {{165, 0.1f}, {140, 0.4f}, {110, 0.7f}, {80, 0.9f},
                    {110, 0.7f}, {140, 0.4f}, {172, 0f}};
            for (float[] phase : phases) {
                // 每个阶段持续 4 帧，整次动作约 1.8s，接近真实节奏
                for (int i = 0; i < 4; i++) {
                    counter.process(pullUp(ts, phase[0], phase[1]));
                    ts += FPS_STEP_MS;
                }
            }
        }
        assertEquals(2, counter.getGoodReps());
    }

    // ---------------- 平板支撑 ----------------

    @Test
    public void timesPlank() {
        PlankTimer timer = new PlankTimer(null);
        long ts = 0;
        for (int i = 0; i < 60; i++) { // 4 秒
            timer.process(plank(ts, 0f, true));
            ts += FPS_STEP_MS;
        }
        long best = timer.getBestHoldMs();
        assertTrue("best=" + best, best >= 3000 && best <= 4000);
        assertEquals(1, timer.getAttempts());
    }

    @Test
    public void sagStopsPlankTiming() {
        PlankTimer timer = new PlankTimer(null);
        long ts = 0;
        for (int i = 0; i < 15; i++) { // 1 秒标准
            timer.process(plank(ts, 0f, true));
            ts += FPS_STEP_MS;
        }
        long afterGood = timer.getBestHoldMs();
        for (int i = 0; i < 40; i++) { // 塌腰 2.6 秒，不应继续计时
            timer.process(plank(ts, 0.16f, true));
            ts += FPS_STEP_MS;
        }
        assertTrue(afterGood > 0);
        assertTrue("塌腰期间不应继续累加", timer.getBestHoldMs() <= afterGood + 900);
        assertTrue(timer.getSagWarnings() > 0);
    }

    // ---------------- 靠墙蹲 ----------------

    @Test
    public void timesWallSit() {
        WallSitTimer timer = new WallSitTimer(null);
        long ts = 0;
        for (int i = 0; i < 8; i++) {          // 站起来
            timer.process(wallSit(ts, 0.25f));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 45; i++) {         // 标准靠墙蹲约 3 秒
            timer.process(wallSit(ts, 0f));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 15; i++) {         // 站起来
            timer.process(wallSit(ts, 0.25f));
            ts += FPS_STEP_MS;
        }
        long best = timer.getBestHoldMs();
        assertTrue("best=" + best, best >= 2400 && best <= 3200);
        assertEquals(1, timer.getAttempts());
        assertTrue(timer.getStability() > 0.8f);
    }

    @Test
    public void wallSitRejectsHighHips() {
        WallSitTimer timer = new WallSitTimer(null);
        long ts = 0;
        for (int i = 0; i < 60; i++) {          // 大腿没水平，等于半站着
            timer.process(wallSit(ts, 0.10f));   // 髋部抬高 ≈0.36×躯干长度 > 0.30
            ts += FPS_STEP_MS;
        }
        assertEquals(0, timer.getBestHoldMs());
        assertEquals(0, timer.getAttempts());
    }

    @Test
    public void wallSitSurvivesShortWobble() {
        WallSitTimer timer = new WallSitTimer(null);
        long ts = 0;
        for (int i = 0; i < 30; i++) {
            timer.process(wallSit(ts, 0f));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 4; i++) {           // 0.26 秒晃动（宽限期内）
            timer.process(wallSit(ts, 0.25f));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 20; i++) {
            timer.process(wallSit(ts, 0f));
            ts += FPS_STEP_MS;
        }
        assertTrue("best=" + timer.getBestHoldMs(), timer.getBestHoldMs() >= 3000);
        assertEquals(1, timer.getAttempts());
    }

    // ---------------- 跑步计步 ----------------

    @Test
    public void countsRunningSteps() {
        RunStepCounter counter = new RunStepCounter(null);
        long ts = 0;
        // 每侧保持 5 帧（约 330ms 一步 → 180 步/分），共 4 个来回
        for (int cycle = 0; cycle < 4; cycle++) {
            for (int i = 0; i < 5; i++) {
                counter.process(running(ts, 0.16f, 0f));
                ts += FPS_STEP_MS;
            }
            for (int i = 0; i < 5; i++) {
                counter.process(running(ts, 0f, 0.16f));
                ts += FPS_STEP_MS;
            }
        }
        assertTrue("steps=" + counter.getGoodReps(), counter.getGoodReps() >= 6);
        float cadence = counter.getCadence();
        assertTrue("cadence=" + cadence, cadence > 150 && cadence < 220);
        assertEquals(counter.getGoodReps() + " 步", counter.getScoreText());
    }

    @Test
    public void standingDoesNotCountSteps() {
        RunStepCounter counter = new RunStepCounter(null);
        long ts = 0;
        for (int i = 0; i < 60; i++) {
            counter.process(standing(ts));
            ts += FPS_STEP_MS;
        }
        assertEquals(0, counter.getGoodReps());
        assertFalse(counter.hasResult());
    }

    @Test
    public void highFrequencyJitterIsNotCounted() {
        RunStepCounter counter = new RunStepCounter(null);
        long ts = 0;
        for (int i = 0; i < 80; i++) {
            boolean left = i % 2 == 0;
            counter.process(running(ts, left ? 0.16f : 0f, left ? 0f : 0.16f));
            ts += 40;
        }
        assertTrue("steps=" + counter.getGoodReps(), counter.getGoodReps() <= 3);
    }

    // ---------------- 倒立 ----------------

    @Test
    public void timesHandstandAcrossShortWobble() {
        HandstandTimer timer = new HandstandTimer(ExerciseType.HANDSTAND, null);
        long ts = 0;
        for (int i = 0; i < 10; i++) {
            timer.process(handstand(ts, 0f, false));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 45; i++) { // 3 秒倒立
            timer.process(handstand(ts, 3f, true));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 4; i++) { // 短暂晃动（宽限期内）
            timer.process(handstand(ts, 0f, false));
            ts += FPS_STEP_MS;
        }
        for (int i = 0; i < 30; i++) { // 再 2 秒
            timer.process(handstand(ts, 3f, true));
            ts += FPS_STEP_MS;
        }
        long best = timer.getBestHoldMs();
        assertTrue("best=" + best, best >= 4600 && best <= 5800);
        assertEquals(1, timer.getAttempts());
        float stability = timer.getStability();
        assertTrue("stability=" + stability, stability >= 0f && stability <= 1f);
    }

    @Test
    public void rejectsTiltedHandstand() {
        HandstandTimer timer = new HandstandTimer(ExerciseType.HANDSTAND, null);
        long ts = 0;
        for (int i = 0; i < 60; i++) {
            timer.process(handstand(ts, 45f, true));
            ts += FPS_STEP_MS;
        }
        assertEquals(0, timer.getBestHoldMs());
        assertEquals(0, timer.getAttempts());
    }

    // ---------------- 身体度量与滤波 ----------------

    @Test
    public void bodyMetricsDetectViewAndSag() {
        BodyMetrics side = BodyMetrics.of(pushUp(0, 90f, 0f, false));
        assertTrue(side.valid);
        assertEquals(BodyMetrics.View.SIDE, side.view);
        assertTrue("sagRatio=" + side.sagRatio, side.sagRatio < 0.02f);
        assertTrue("wristDrop=" + side.wristDropRatio, side.wristDropRatio > 0);

        BodyMetrics sagged = BodyMetrics.of(pushUp(0, 90f, 0.12f, false));
        assertTrue("sagRatio=" + sagged.sagRatio, sagged.sagRatio > 0.1f);

        BodyMetrics stand = BodyMetrics.of(standing(0));
        assertTrue(stand.torsoAngleFromVertical() < 20f);
    }

    @Test
    public void smootherRemovesJitterAndHoldsShortDropouts() {
        PoseSmoother smoother = new PoseSmoother();
        long ts = 0;
        PoseFrame first = smoother.smooth(pushUp(ts, 160f, 0f, false));
        assertNotNull(first);
        ts += FPS_STEP_MS;

        // 交替跳变：滤波后肩部 x 应接近原值
        float baseX = first.getX(PoseFrame.LEFT_SHOULDER);
        PoseFrame smoothed = null;
        for (int i = 0; i < 6; i++) {
            PoseFrame raw = pushUp(ts, i % 2 == 0 ? 100f : 168f, 0f, false);
            smoothed = smoother.smooth(raw);
            ts += FPS_STEP_MS;
        }
        assertNotNull(smoothed);
        assertTrue(Math.abs(smoothed.getX(PoseFrame.LEFT_SHOULDER) - baseX) < 0.05f);

        // 短暂丢失（置信度 0）：应保持上一帧位置
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        PoseFrame lost = PoseFrame.of(ts, x, y, z, c);
        PoseFrame held = smoother.smooth(lost);
        assertTrue("短期丢失时应保持上一帧关键点", held.isVisible());
    }

    @Test
    public void smootherRejectsJump() {
        PoseSmoother smoother = new PoseSmoother();
        long ts = 0;
        smoother.smooth(pushUp(ts, 160f, 0f, false));
        ts += FPS_STEP_MS;
        // 突然跳到画面另一侧：应被判定为跳变并丢弃
        float[] x = new float[PoseFrame.LANDMARK_COUNT];
        float[] y = new float[PoseFrame.LANDMARK_COUNT];
        float[] z = new float[PoseFrame.LANDMARK_COUNT];
        float[] c = new float[PoseFrame.LANDMARK_COUNT];
        for (int i = 0; i < PoseFrame.LANDMARK_COUNT; i++) {
            x[i] = 0.95f;
            y[i] = 0.05f;
            c[i] = 0.9f;
        }
        smoother.smooth(PoseFrame.of(ts, x, y, z, c));
        assertTrue(smoother.getRejectedJumps() > 0);
    }

    // ---------------- 回放与持久化 ----------------

    @Test
    public void buildsReplayTimelineForAllTypes() {
        List<PoseFrame> frames = new ArrayList<>();
        long ts = 0;
        for (int rep = 0; rep < 3; rep++) {
            for (float angle : repProfile(90f, 168f, 8)) {
                frames.add(pushUp(ts, angle, 0f, false));
                ts += FPS_STEP_MS;
            }
            for (int i = 0; i < 3; i++) {
                frames.add(pushUp(ts, 168f, 0f, false));
                ts += FPS_STEP_MS;
            }
        }
        ReplayTimeline timeline = ReplayTimeline.build(frames, ExerciseType.PUSHUP, "");
        int last = timeline.size() - 1;
        assertEquals(3, timeline.getGoodRepsAt(last));
        assertTrue(timeline.getDurationMs() > 0);

        // 其他动作也能构建时间轴（防止工厂遗漏新动作）
        for (ExerciseType type : ExerciseType.values()) {
            ReplayTimeline t = ReplayTimeline.build(frames, type, "hands");
            assertNotNull(type.getKey(), t);
            assertEquals(frames.size(), t.size());
        }
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
            PoseFrame frame = pushUp(ts, 90f + i * 3f, 0f, false);
            writer.addFrame(frame);
            originals.add(frame);
            ts += FPS_STEP_MS;
        }
        writer.close();
        PoseLog log = PoseLog.load(file);
        assertNotNull(log);
        assertEquals(originals.size(), log.getFrames().size());
        assertEquals(720, log.getViewWidth());
        PoseFrame restored = log.getFrames().get(5);
        assertEquals(originals.get(5).getX(PoseFrame.LEFT_SHOULDER),
                restored.getX(PoseFrame.LEFT_SHOULDER), 0.0001f);
        assertTrue(log.getDurationMs() > 0);
    }

    @Test
    public void exerciseTypesAreStable() {
        assertEquals(ExerciseType.HANDSTAND, ExerciseType.fromKey("handstand"));
        assertEquals(ExerciseType.WALL_HANDSTAND, ExerciseType.fromKey("wall_handstand"));
        assertEquals(ExerciseType.CHAIR_PUSHUP, ExerciseType.fromKey("chair_pushup"));
        assertEquals(ExerciseType.PUSHUP, ExerciseType.fromKey("pushup"));
        assertEquals(ExerciseType.BAD_PUSHUP, ExerciseType.fromKey("bad_pushup"));
        assertEquals(ExerciseType.PLANK, ExerciseType.fromKey("plank"));
        assertEquals(ExerciseType.SQUAT, ExerciseType.fromKey("squat"));
        assertEquals(ExerciseType.PULLUP, ExerciseType.fromKey("pullup"));
        assertEquals(ExerciseType.WALL_SIT, ExerciseType.fromKey("wall_sit"));
        assertEquals(ExerciseType.RUNNING, ExerciseType.fromKey("running"));
        assertEquals(ExerciseType.PUSHUP, ExerciseType.fromKey("unknown"));
        assertEquals(10, ExerciseType.values().length);
        assertTrue(ExerciseType.PLANK.isTimerExercise());
        assertFalse(ExerciseType.SQUAT.isTimerExercise());
        assertTrue(ExerciseType.WALL_HANDSTAND.isWallExercise());
        assertTrue(ExerciseType.WALL_SIT.isTimerExercise());
        assertEquals("步", ExerciseType.RUNNING.getUnitLabel());
        assertFalse(ExerciseType.RUNNING.isTimerExercise());
    }
}
