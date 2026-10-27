package com.zxw.fitness.data;

import com.zxw.fitness.model.PoseFrame;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 已写入磁盘的骨架录制数据，用于回放。
 */
public class PoseLog {

    private final int viewWidth;
    private final int viewHeight;
    private final List<PoseFrame> frames;
    private final long durationMs;

    private PoseLog(int viewWidth, int viewHeight, List<PoseFrame> frames, long durationMs) {
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.frames = frames;
        this.durationMs = durationMs;
    }

    public int getViewWidth() {
        return viewWidth;
    }

    public int getViewHeight() {
        return viewHeight;
    }

    public List<PoseFrame> getFrames() {
        return frames;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    /**
     * 读取骨架文件。
     *
     * @return 解析失败时返回 null
     */
    public static PoseLog load(File file) {
        if (file == null || !file.exists() || file.length() < 20) {
            return null;
        }
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 64 * 1024));
            if (in.readInt() != PoseLogWriter.MAGIC) {
                return null;
            }
            int version = in.readInt();
            if (version != PoseLogWriter.VERSION) {
                return null;
            }
            int width = in.readInt();
            int height = in.readInt();
            List<PoseFrame> frames = new ArrayList<>();
            float[] compact = new float[PoseFrame.LANDMARK_SUBSET.length * 4];
            long last = 0;
            while (true) {
                int offset;
                try {
                    offset = in.readInt();
                } catch (IOException eof) {
                    break;
                }
                boolean visible = in.readByte() != 0;
                for (int i = 0; i < compact.length; i++) {
                    compact[i] = Float.intBitsToFloat(in.readInt());
                }
                PoseFrame frame = PoseFrame.fromCompactArray(offset, compact);
                if (!visible) {
                    frame = PoseFrame.empty(offset);
                }
                frames.add(frame);
                last = offset;
            }
            return new PoseLog(width, height, frames, last);
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }
}
