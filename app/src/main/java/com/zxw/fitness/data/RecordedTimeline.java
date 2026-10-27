package com.zxw.fitness.data;

import com.zxw.fitness.analysis.AlgorithmVersion;
import com.zxw.fitness.analysis.ReplayTimeline;
import com.zxw.fitness.analysis.ReplayTimeline.FrameState;
import com.zxw.fitness.model.CalibrationChange;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PersonalCalibration;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * 录制时保存的逐帧判定快照。文件结尾校验骨架、元数据和自身；不完整或版本不匹配时绝不复用。
 * 和骨架分开保存，保留原有 PoseLog v1/v2 格式及旧记录兼容性。
 */
public final class RecordedTimeline {
    private static final int MAGIC = 0x544c494e; // TLIN
    private static final int FORMAT = 1;
    private static final int FRAME = 1, CALIBRATION = 2, END = 3;
    private static final int MAX_FRAMES = 200000;
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final int MAX_STATUS_CHARS = 2048;

    private RecordedTimeline() { }

    public static final class Writer {
        private final File file;
        private final CRC32 checksum = new CRC32();
        private DataOutputStream out;
        private boolean failed;
        private int frames;
        private long baseMs = -1;
        private long previousMs = -1;

        public Writer(File file, Session session) throws IOException {
            this.file = file;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("无法创建逐帧记录目录");
            }
            out = new DataOutputStream(new CheckedOutputStream(
                    new BufferedOutputStream(new FileOutputStream(file), 16 * 1024), checksum));
            try {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT);
                out.writeUTF(session.getAlgorithmVersion());
                out.writeLong(session.getId());
            } catch (IOException | RuntimeException e) {
                discard();
                throw e;
            }
        }

        public void addFrame(PoseFrame frame, FrameState state) {
            if (failed || out == null) return;
            try {
                if (frame == null || !validState(state) || frames >= MAX_FRAMES) throw new IOException();
                long now = frame.getTimestampMs();
                if (baseMs < 0) baseMs = now;
                long offset = now - baseMs;
                if (now < 0 || now < previousMs || offset < 0 || offset > Integer.MAX_VALUE) {
                    throw new IOException();
                }
                out.writeByte(FRAME);
                out.writeInt((int) offset);
                writeState(out, state);
                previousMs = now;
                frames++;
            } catch (IOException | RuntimeException error) {
                failed = true;
            }
        }

        /** 点击个人校准发生在最近一帧之后：保存校准后状态和帧下标。 */
        public void addCalibration(FrameState state) {
            if (failed || out == null) return;
            try {
                if (frames == 0 || !validState(state)) throw new IOException();
                out.writeByte(CALIBRATION);
                out.writeInt(frames - 1);
                writeState(out, state);
            } catch (IOException | RuntimeException error) {
                failed = true;
            }
        }

        /** 骨架已成功关闭后才写完整尾标；失败时移除缓存而不影响训练成绩。 */
        public boolean finish(File poseFile, Session session, int expectedFrames) {
            if (failed || out == null || frames == 0 || frames != expectedFrames) {
                discard();
                return false;
            }
            try {
                if (poseFile == null || !poseFile.isFile()) throw new IOException();
                out.writeByte(END);
                out.writeInt(frames);
                out.writeLong(poseFile.length());
                out.writeLong(fileChecksum(poseFile));
                out.writeLong(metadataChecksum(session));
                out.flush();
                long value = checksum.getValue();
                out.writeLong(value);
                out.close();
                out = null;
                if (file.length() > MAX_BYTES) throw new IOException();
                return true;
            } catch (IOException | RuntimeException error) {
                discard();
                return false;
            }
        }

        public void discard() {
            failed = true;
            if (out != null) {
                try { out.close(); } catch (IOException ignored) { }
                out = null;
            }
            if (file != null && file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /** 不复用旧版、损坏或未完成的缓存；返回 null 由调用方选择重算/降级。 */
    public static ReplayTimeline load(File cache, File poseFile, PoseLog poseLog, Session session) {
        if (!AlgorithmVersion.matches(session) || cache == null || !cache.isFile()
                || cache.length() <= 0 || cache.length() > MAX_BYTES || poseLog == null
                || poseLog.isEmpty() || poseLog.getFrames().size() > MAX_FRAMES
                || poseFile == null || !poseFile.isFile()) return null;
        CRC32 checksum = new CRC32();
        try (DataInputStream in = new DataInputStream(new CheckedInputStream(
                new BufferedInputStream(new FileInputStream(cache), 16 * 1024), checksum))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT
                    || !AlgorithmVersion.CURRENT.equals(in.readUTF())
                    || in.readLong() != session.getId()) return null;
            List<PoseFrame> poses = poseLog.getFrames();
            List<FrameState> states = new ArrayList<>();
            int calibrationCount = 0;
            long previous = -1;
            for (;;) {
                int tag = in.readUnsignedByte();
                if (tag == FRAME) {
                    if (states.size() >= poses.size()) return null;
                    int offset = in.readInt();
                    FrameState state = readState(in);
                    if (offset < 0 || offset < previous || poses.get(states.size()).getTimestampMs() != offset
                            || !validState(state)) return null;
                    previous = offset;
                    states.add(state);
                } else if (tag == CALIBRATION) {
                    int index = in.readInt();
                    FrameState state = readState(in);
                    if (index != states.size() - 1 || index < 0 || !validState(state)
                            || calibrationCount >= session.getCalibrationChanges().size()
                            || session.getCalibrationChanges().get(calibrationCount).frameIndex != index) return null;
                    states.set(index, state);
                    calibrationCount++;
                } else if (tag == END) {
                    int total = in.readInt();
                    long poseLength = in.readLong();
                    long poseCrc = in.readLong();
                    long metadataCrc = in.readLong();
                    long actualChecksum = checksum.getValue();
                    long expectedChecksum = in.readLong();
                    if (in.read() != -1 || expectedChecksum != actualChecksum || total != states.size()
                            || total != poses.size() || total == 0
                            || calibrationCount != session.getCalibrationChanges().size()
                            || poseLength != poseFile.length() || poseCrc != fileChecksum(poseFile)
                            || metadataCrc != metadataChecksum(session)
                            || !matchesSummary(states.get(states.size() - 1), session)) return null;
                    return ReplayTimeline.fromRecorded(poses, states);
                } else {
                    return null;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean matchesSummary(FrameState last, Session session) {
        ExerciseType type = session.getExerciseType();
        if (!type.isTimerExercise()) {
            return last.good == session.getReps() && last.bad == session.getBadReps();
        }
        return last.holdMs == (type == ExerciseType.WALL_SIT
                ? session.getTotalHoldMs() : session.getHoldMs());
    }

    private static boolean validState(FrameState state) {
        return state != null && !Float.isNaN(state.score) && !Float.isInfinite(state.score)
                && !Float.isNaN(state.progress) && !Float.isInfinite(state.progress)
                && state.progress >= 0 && state.progress <= 1 && state.good >= 0 && state.bad >= 0
                && state.holdMs >= 0 && state.status.length() <= MAX_STATUS_CHARS;
    }

    private static void writeState(DataOutputStream out, FrameState state) throws IOException {
        out.writeFloat(state.score);
        out.writeFloat(state.progress);
        out.writeInt(state.good);
        out.writeInt(state.bad);
        out.writeLong(state.holdMs);
        out.writeUTF(state.status);
    }

    private static FrameState readState(DataInputStream in) throws IOException {
        float score = in.readFloat();
        float progress = in.readFloat();
        int good = in.readInt();
        int bad = in.readInt();
        long hold = in.readLong();
        String status = in.readUTF();
        return new FrameState(score, progress, status, good, bad, hold);
    }

    private static long fileChecksum(File file) throws IOException {
        CRC32 crc = new CRC32();
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] bytes = new byte[16 * 1024];
            int count;
            while ((count = in.read(bytes)) != -1) crc.update(bytes, 0, count);
        }
        return crc.getValue();
    }

    /** 固定字段顺序，避免 JSON 键顺序影响校准快照验证。 */
    private static long metadataChecksum(Session session) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(session.getUserId() == null ? "" : session.getUserId());
        out.writeUTF(session.getExerciseKey() == null ? "" : session.getExerciseKey());
        out.writeUTF(session.getVariant());
        writeCalibration(out, session.getInitialCalibration());
        out.writeInt(session.getCalibrationChanges().size());
        for (CalibrationChange change : session.getCalibrationChanges()) {
            out.writeInt(change.frameIndex);
            writeCalibration(out, change.calibration);
        }
        out.flush();
        CRC32 crc = new CRC32();
        crc.update(bytes.toByteArray());
        return crc.getValue();
    }

    private static void writeCalibration(DataOutputStream out, PersonalCalibration c) throws IOException {
        out.writeBoolean(c != null);
        if (c == null) return;
        out.writeUTF(c.getType().getKey());
        out.writeUTF(c.getVariant());
        out.writeFloat(c.getTargetDepth());
        out.writeFloat(c.getStartingAngle());
        out.writeFloat(c.getCapturedAngle());
        out.writeFloat(c.getCapturedMovement());
        out.writeFloat(c.getMaxForm());
        out.writeFloat(c.getMinHipAngle());
        out.writeFloat(c.getMinMovement());
        out.writeBoolean(c.isKneelingAllowed());
        out.writeBoolean(c.hasFormWarning());
    }
}
