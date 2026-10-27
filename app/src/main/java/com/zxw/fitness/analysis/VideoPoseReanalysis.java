package com.zxw.fitness.analysis;

import com.zxw.fitness.model.PoseFrame;

import java.util.Collections;
import java.util.List;

/** 仅驻留内存的视频重识别结果，不写回录像、骨架文件或历史成绩。 */
public final class VideoPoseReanalysis {
    private final List<PoseFrame> rawFrames;
    private final List<PoseFrame> filteredFrames;
    private final List<boolean[]> pendingJumps;
    private final ReplayTimeline timeline;
    private final int sourceWidth;
    private final int sourceHeight;
    private final long durationMs;
    private final int intervalMs;
    private final int missingFrames;

    VideoPoseReanalysis(List<PoseFrame> rawFrames, List<PoseFrame> filteredFrames,
                        List<boolean[]> pendingJumps, ReplayTimeline timeline,
                        int sourceWidth, int sourceHeight, long durationMs,
                        int intervalMs, int missingFrames) {
        this.rawFrames = Collections.unmodifiableList(rawFrames);
        this.filteredFrames = Collections.unmodifiableList(filteredFrames);
        this.pendingJumps = Collections.unmodifiableList(pendingJumps);
        this.timeline = timeline;
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.durationMs = durationMs;
        this.intervalMs = intervalMs;
        this.missingFrames = missingFrames;
    }

    public int size() { return rawFrames.size(); }
    public PoseFrame getRawFrame(int index) { return rawFrames.get(index); }
    public PoseFrame getFilteredFrame(int index) { return filteredFrames.get(index); }
    /** 原始显示上标出暂被滤波器拒绝的点；只读使用。 */
    public boolean[] getPendingJumps(int index) { return pendingJumps.get(index); }
    public ReplayTimeline getTimeline() { return timeline; }
    public int getSourceWidth() { return sourceWidth; }
    public int getSourceHeight() { return sourceHeight; }
    public long getDurationMs() { return durationMs; }
    public int getIntervalMs() { return intervalMs; }
    public int getMissingFrames() { return missingFrames; }
}
