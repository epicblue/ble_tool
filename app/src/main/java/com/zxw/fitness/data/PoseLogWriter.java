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
 * 每帧：timeOffsetMs(int) + visible(byte) + 17*4 个 float(x,y,z,confidence)。</p>
 */
public class PoseLogWriter {

    public static final int MAGIC = 0x504F5345; // "POSE"
    public static final int VERSION = 1;

    private OutputStream out;
    private long startTimeMs = -1;
    private int frameCount;
    private boolean closed;

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
            float[] compact = frame.toCompactArray();
            for (float value : compact) {
                writeInt(Float.floatToIntBits(value));
            }
            frameCount++;
        } catch (IOException e) {
            close();
        }
    }

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
                out.flush();
                out.close();
            } catch (IOException ignored) {
                // ignore
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
