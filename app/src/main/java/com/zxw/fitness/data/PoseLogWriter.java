package com.zxw.fitness.data;

import com.zxw.fitness.model.PoseFrame;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 把每帧关键点写入二进制文件（体积小、解析快），供回放时重绘骨架。
 *
 * <p>文件结构：magic("POSE") + version(int) + 画面宽(int) + 画面高(int) + 帧数组。
 * v2 每帧：timeOffsetMs(int) + visible(byte) + roll(float) + 17*4 个 float(x,y,z,confidence)。</p>
 */
public class PoseLogWriter {

    public static final int MAGIC = 0x504F5345; // "POSE"
    public static final int VERSION = 2;

    private OutputStream out;
    private long startTimeMs = -1;
    private int frameCount;
    private boolean closed;
    private boolean failed;

    public PoseLogWriter(File file, int viewWidth, int viewHeight) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        out = new BufferedOutputStream(new FileOutputStream(file), 64 * 1024);
        writeInt(MAGIC);
        writeInt(VERSION);
        writeInt(viewWidth);
        writeInt(viewHeight);
    }

    public synchronized void addFrame(PoseFrame frame) {
        addFrame(frame, 0f);
    }

    public synchronized void addFrame(PoseFrame frame, float rollDegrees) {
        if (closed || out == null || frame == null) {
            return;
        }
        try {
            if (startTimeMs < 0) {
                startTimeMs = frame.getTimestampMs();
            }
            int offset = (int) Math.max(0, frame.getTimestampMs() - startTimeMs);
            writeInt(offset);
            out.write(frame.isVisible() ? 1 : 0);
            writeInt(Float.floatToIntBits(rollDegrees));
            float[] compact = frame.toCompactArray();
            for (float value : compact) {
                writeInt(Float.floatToIntBits(value));
            }
            frameCount++;
        } catch (IOException e) {
            failed = true;
            close();
        }
    }

    /** 主动写出缓冲区，供个人校准确认事件可被回放。 */
    public synchronized boolean flush() {
        if (closed || out == null || failed) return false;
        try {
            out.flush();
            return true;
        } catch (IOException e) {
            failed = true;
            close();
            return false;
        }
    }

    public synchronized boolean hasError() { return failed; }

    public int getFrameCount() {
        return frameCount;
    }

    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                failed = true;
            }
            out = null;
        }
    }

    private void writeInt(int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
