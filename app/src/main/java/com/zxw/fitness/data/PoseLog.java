package com.zxw.fitness.data;

import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.pose.GravityAlign;

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
    private final List<PoseFrame> displayFrames;
    private final boolean legacyPixelCoordinates;
    private final long durationMs;

    private PoseLog(int viewWidth, int viewHeight, List<PoseFrame> frames,
                    List<PoseFrame> displayFrames, boolean legacyPixelCoordinates, long durationMs) {
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.frames = frames;
        this.displayFrames = displayFrames;
        this.legacyPixelCoordinates = legacyPixelCoordinates;
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

    /** 与视频画面同一坐标系的骨架；判定仍使用 getFrames() 的重力对齐坐标。 */
    public List<PoseFrame> getDisplayFrames() { return displayFrames; }

    public boolean isLegacyPixelCoordinates() { return legacyPixelCoordinates; }

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
            if (version != 1 && version != PoseLogWriter.VERSION) {
                return null;
            }
            int width = in.readInt();
            int height = in.readInt();
            if (width <= 0 || height <= 0) return null;
            List<PoseFrame> frames = new ArrayList<>();
            List<PoseFrame> displayFrames = new ArrayList<>();
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
                float roll = version >= 2 ? Float.intBitsToFloat(in.readInt()) : 0f;
                for (int i = 0; i < compact.length; i++) {
                    compact[i] = Float.intBitsToFloat(in.readInt());
                }
                PoseFrame frame = PoseFrame.fromCompactArray(offset, compact);
                if (!visible) {
                    frame = PoseFrame.empty(offset);
                }
                frames.add(frame);
                // v1 没记录横滚，原样回放；v2 逆向旋转，叠加到未旋转的录像上。
                PoseFrame display = version >= 2 && visible && Math.abs(roll) >= 0.01f
                        ? GravityAlign.rotate(frame, -roll, 0.5f, height / (2f * width)) : frame;
                displayFrames.add(display);
                last = offset;
            }
            return new PoseLog(width, height, frames, displayFrames, version == 1, last);
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
