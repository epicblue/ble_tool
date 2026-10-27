package com.zxw.fitness.analysis;

import com.zxw.fitness.model.PoseFrame;

/**
 * 相机无回调时没有“不合格帧”可传给计时器/状态机；在长间隔后补一次失效通知。
 * 不新增日志帧，回放根据同样的时间戳间隔重现该通知。
 */
public final class FrameGapGuard {
    /** 小于该间隔按普通掉帧容忍；超过则不能把空白直接当作持续合格。 */
    public static final long MAX_FRAME_GAP_MS = 500;
    private static final long ROLLBACK_RESET_MS = 1000;
    private long previousMs = -1;

    public void reset() { previousMs = -1; }

    public void process(ExerciseAnalyzer analyzer, PoseFrame frame) {
        if (analyzer == null || frame == null) return;
        long now = frame.getTimestampMs();
        if (previousMs >= 0) {
            if (now < previousMs) {
                // 时间回退时终止旧状态，然后以新时间重新开始。
                analyzer.process(PoseFrame.empty(previousMs + ROLLBACK_RESET_MS));
            } else if (now - previousMs > MAX_FRAME_GAP_MS) {
                analyzer.process(PoseFrame.empty(now));
            }
        }
        previousMs = now;
        analyzer.process(frame);
    }
}
