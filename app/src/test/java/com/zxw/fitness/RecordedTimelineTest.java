package com.zxw.fitness;

import com.zxw.fitness.analysis.AlgorithmVersion;
import com.zxw.fitness.analysis.FrameGapGuard;
import com.zxw.fitness.analysis.HoldAnalyzer;
import com.zxw.fitness.analysis.PersonalCalibrationAnalyzer;
import com.zxw.fitness.analysis.RepAnalyzer;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.ReplayTimeline.FrameState;
import com.zxw.fitness.analysis.SquatCounter;
import com.zxw.fitness.analysis.WallSitTimer;
import com.zxw.fitness.data.PoseLog;
import com.zxw.fitness.data.PoseLogWriter;
import com.zxw.fitness.data.RecordedTimeline;
import com.zxw.fitness.data.RecordedTimeline.Writer;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import static com.zxw.fitness.SyntheticPoses.*;
import static org.junit.Assert.*;

/** 真正的二进制文件、校准快照和回放时间轴；不依赖 Android/ML Kit。 */
public class RecordedTimelineTest {
    private File dir, pose, cache;
    private Session session;
    private PoseLog loaded;
    private List<FrameState> expected;

    @Before public void createDir() throws Exception {
        dir = Files.createTempDirectory("timeline-test").toFile();
        pose = new File(dir, "pose.dat");
        cache = new File(dir, "timeline.dat");
    }

    @After public void clean() {
        if (dir != null && dir.listFiles() != null) for (File f : dir.listFiles()) f.delete();
        if (dir != null) dir.delete();
    }

    private void record(ExerciseType type, boolean calibrate) throws Exception {
        session = new Session();
        session.setId(42);
        session.setUserId("kid");
        session.setExerciseType(type);
        session.setVariant("");
        session.setAlgorithmVersion(AlgorithmVersion.CURRENT);
        RepAnalyzer rep = type.isTimerExercise() ? null : new SquatCounter(null);
        HoldAnalyzer hold = type.isTimerExercise() ? new WallSitTimer(null) : null;
        FrameGapGuard guard = new FrameGapGuard();
        PoseLogWriter poseWriter = new PoseLogWriter(pose, 720, 1280);
        Writer writer = new Writer(cache, session);
        expected = new ArrayList<>();
        PersonalCalibration personal = new PersonalCalibration(ExerciseType.SQUAT, "", .6f,
                130f, .2f, 55f, 0f, .08f, false, false);
        assertTrue(personal.isValid());
        for (int i = 0; i < 50; i++) {
            long t = 10000 + i * 100L;
            PoseFrame f = type.isTimerExercise() ? wallSit(t, 0) : squat(t, 165f, 0);
            poseWriter.addFrame(f, 1.5f);
            guard.process(rep != null ? rep : hold, f);
            FrameState state = ReplayTimeline.capture(type, rep, hold);
            writer.addFrame(f, state);
            expected.add(state);
            if (calibrate && i == 18) {
                session.addCalibrationChange(i, personal);
                ((PersonalCalibrationAnalyzer) rep).applyCalibrationEvent(personal);
                state = ReplayTimeline.capture(type, rep, hold);
                writer.addCalibration(state);
                expected.set(i, state);
            }
        }
        poseWriter.close();
        assertFalse(poseWriter.hasError());
        if (rep != null) {
            session.setReps(rep.getGoodReps());
            session.setBadReps(rep.getBadReps());
        } else {
            session.setHoldMs(hold.getBestHoldMs());
            session.setTotalHoldMs(hold.getTotalHoldMs());
        }
        assertTrue(writer.finish(pose, session, 50));
        loaded = PoseLog.load(pose);
        assertNotNull(loaded);
    }

    @Test public void matchingVersionRestoresEveryRecordedStateIncludingCalibration() throws Exception {
        record(ExerciseType.SQUAT, true);
        ReplayTimeline replay = RecordedTimeline.load(cache, pose, loaded, session);
        assertNotNull(replay);
        assertEquals(50, replay.size());
        for (int i = 0; i < replay.size(); i++) {
            FrameState s = expected.get(i);
            assertEquals(s.good, replay.getGoodRepsAt(i));
            assertEquals(s.bad, replay.getBadRepsAt(i));
            assertEquals(s.status, replay.getStatusAt(i));
            assertEquals(s.score, replay.getScoreAt(i), .00001f);
            assertEquals(s.progress, replay.getProgressAt(i), .00001f);
        }
        assertEquals(0, replay.getGoodRepsAt(17));
        assertEquals(1, replay.getGoodRepsAt(18));
        assertEquals(18, replay.indexAt(1800));
        // 同样数据用现有判定器重算应仍对应校准事件，缓存与它各自独立。
        ReplayTimeline recomputed = ReplayTimeline.build(loaded.getFrames(), session);
        assertEquals(recomputed.getGoodRepsAt(18), replay.getGoodRepsAt(18));
    }

    @Test public void wallSitStoresCumulativeRatherThanLongest() throws Exception {
        record(ExerciseType.WALL_SIT, false);
        ReplayTimeline replay = RecordedTimeline.load(cache, pose, loaded, session);
        assertNotNull(replay);
        for (int i = 0; i < replay.size(); i++) {
            assertEquals(expected.get(i).holdMs, replay.getHoldMsAt(i));
        }
        assertEquals(session.getTotalHoldMs(), replay.getHoldMsAt(replay.size() - 1));
    }

    @Test public void oldDifferentMissingAndMismatchedRecordsAreNeverReused() throws Exception {
        record(ExerciseType.SQUAT, true);
        session.setAlgorithmVersion(null);
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
        session.setAlgorithmVersion("old-version");
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
        session.setAlgorithmVersion(AlgorithmVersion.CURRENT);
        assertNull(RecordedTimeline.load(null, pose, loaded, session));
        assertNull(RecordedTimeline.load(cache, null, loaded, session));
        assertNull(RecordedTimeline.load(cache, pose, null, session));
        session.setUserId("other-child");
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
        session.setUserId("kid");
        session.setId(43);
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
        session.setId(42);
        session.setReps(99);
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
    }

    @Test public void changedCalibrationAndPoseFileAreNotTreatedAsMatching() throws Exception {
        record(ExerciseType.SQUAT, true);
        session.setVariant("changed");
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
        session.setVariant("");
        assertNotNull(RecordedTimeline.load(cache, pose, loaded, session));
        try (RandomAccessFile f = new RandomAccessFile(pose, "rw")) {
            f.seek(30); f.writeByte(f.readByte() ^ 1);
        }
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
    }

    @Test public void incompleteCorruptAndExtraBytesFailClosed() throws Exception {
        record(ExerciseType.SQUAT, false);
        try (RandomAccessFile f = new RandomAccessFile(cache, "rw")) { f.setLength(f.length() - 1); }
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
        record(ExerciseType.SQUAT, false);
        try (RandomAccessFile f = new RandomAccessFile(cache, "rw")) {
            f.seek(30); int b = f.read(); f.seek(30); f.write(b ^ 1);
        }
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
        record(ExerciseType.SQUAT, false);
        try (RandomAccessFile f = new RandomAccessFile(cache, "rw")) { f.seek(f.length()); f.write(1); }
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
    }

    @Test public void poseLogRejectsPartialTailAndOutOfOrderTimes() throws Exception {
        record(ExerciseType.SQUAT, false);
        byte[] valid = Files.readAllBytes(pose.toPath());
        for (int n = 1; n <= 3; n++) {
            byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + n);
            Files.write(pose.toPath(), trailing);
            assertNull("不能把半个帧时间当文件结束", PoseLog.load(pose));
        }
        Files.write(pose.toPath(), valid);
        try (RandomAccessFile f = new RandomAccessFile(pose, "rw")) {
            f.seek(8); f.writeInt(0);
        }
        assertNull("无效画面尺寸不是可用骨架", PoseLog.load(pose));
        Files.write(pose.toPath(), valid);
        // v2 每帧 4 + 1 + 4 + 17*4*4 字节；第三帧时间回退到 0。
        try (RandomAccessFile f = new RandomAccessFile(pose, "rw")) {
            f.seek(16 + 2L * (4 + 1 + 4 + 17 * 4 * 4)); f.writeInt(0);
        }
        assertNull("回退时间不能交给二分查找", PoseLog.load(pose));
    }

    @Test public void poseWriterMarksRollbackAndOverflowAsIncomplete() throws Exception {
        PoseLogWriter writer = new PoseLogWriter(pose, 720, 1280);
        writer.addFrame(standing(1000)); writer.addFrame(standing(1300)); writer.addFrame(standing(1200));
        assertTrue(writer.hasError()); assertEquals(2, writer.getFrameCount());
        writer = new PoseLogWriter(pose, 720, 1280);
        writer.addFrame(standing(0)); writer.addFrame(standing((long) Integer.MAX_VALUE + 1));
        assertTrue(writer.hasError()); assertEquals(1, writer.getFrameCount());
    }

    @Test public void checksumRejectsParseableStatusCorruption() throws Exception {
        record(ExerciseType.SQUAT, false);
        byte[] bytes = Files.readAllBytes(cache.toPath());
        byte[] status = expected.get(0).status.getBytes("UTF-8");
        assertTrue(status.length > 0);
        int position = -1;
        for (int i = 0; i + status.length <= bytes.length; i++) {
            boolean matches = true;
            for (int j = 0; j < status.length; j++) {
                if (bytes[i + j] != status[j]) { matches = false; break; }
            }
            if (matches) { position = i; break; }
        }
        assertTrue(position >= 0);
        // 改动一个字符但保持 UTF-8 结构及长度，解析、帧数和最终成绩仍正常。
        bytes[position + status.length - 1] ^= 1;
        Files.write(cache.toPath(), bytes);
        assertNull(RecordedTimeline.load(cache, pose, loaded, session));
    }

    @Test public void unfinishedMismatchedAndNonmonotonicWritersDiscardCache() throws Exception {
        session = new Session(); session.setId(42); session.setUserId("kid");
        session.setExerciseType(ExerciseType.SQUAT); session.setAlgorithmVersion(AlgorithmVersion.CURRENT);
        PoseLogWriter p = new PoseLogWriter(pose, 720, 1280);
        FrameState state = new FrameState(0, 0, "ok", 0, 0, 0);
        Writer w = new Writer(cache, session);
        PoseFrame a = standing(1000), b = standing(900);
        p.addFrame(a); p.addFrame(b); p.close();
        w.addFrame(a, state); w.addFrame(b, state);
        assertFalse(w.finish(pose, session, 2));
        assertFalse(cache.exists());
        w = new Writer(cache, session);
        w.addFrame(a, state);
        assertFalse(w.finish(pose, session, 2));
        assertFalse(cache.exists());
    }
}
