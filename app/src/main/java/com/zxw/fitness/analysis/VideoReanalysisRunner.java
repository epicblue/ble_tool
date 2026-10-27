package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.PoseFrame;
import com.zxw.fitness.model.Session;
import com.zxw.fitness.pose.PoseSmoother;
import com.zxw.fitness.pose.FrozenPoseRecovery;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 可测试的离线视频识别流水线。帧源和检测器由平台层提供，严格按视频时间顺序调用。
 * 所有结果只保存在内存中；不会打开任何写入流或修改 Session。
 */
public final class VideoReanalysisRunner {
    public static final int DEFAULT_INTERVAL_MS = 100;
    public static final int MAX_INTERVAL_MS = 350;
    public static final int MAX_SAMPLES = 6000;

    private VideoReanalysisRunner() { }

    public interface ImageFrame<T> {
        T image();
        int width();
        int height();
        void close();
    }

    public interface FrameSource<T> {
        long durationMs();
        /** 接近指定视频时间的画面；无法解码该帧时可返回 null。 */
        ImageFrame<T> frameAt(long timestampMs) throws Exception;
        void close();
    }

    public interface PoseEngine<T> {
        /** 调用返回时检测已结束，图像才可以安全释放。 */
        PoseFrame detect(ImageFrame<T> frame, long timestampMs) throws Exception;
        void close();
    }

    public interface Factory<T> {
        FrameSource<T> openSource() throws Exception;
        PoseEngine<T> openEngine(boolean detectPersonEveryFrame) throws Exception;
    }

    public interface Cancellation {
        boolean isCancelled();
    }

    public interface Progress {
        void onProgress(int complete, int total);
    }

    public static final class Cancelled extends Exception {
        public Cancelled() { super("已取消视频重识别"); }
    }

    /** 长录像自动降低采样率并限制内存；过长时拒绝，而不是无限制占用设备。 */
    public static int sampleIntervalMs(long durationMs) throws IOException {
        if (durationMs <= 0) throw new IOException("无法读取视频时长");
        // 先比较再计算，避免损坏元数据导致整数溢出。
        if (durationMs > (long) MAX_SAMPLES * MAX_INTERVAL_MS) {
            throw new IOException("录像超过 35 分钟，请先使用较短的录像验证");
        }
        long needed = (durationMs + MAX_SAMPLES - 1) / MAX_SAMPLES;
        return (int) Math.max(DEFAULT_INTERVAL_MS, needed);
    }

    private static void checkCancelled(Cancellation cancellation) throws Cancelled {
        if (cancellation != null && cancellation.isCancelled()) throw new Cancelled();
    }

    public static <T> VideoPoseReanalysis run(Factory<T> factory, Session session,
                                               Cancellation cancellation, Progress progress)
            throws Exception {
        checkCancelled(cancellation);
        FrameSource<T> source = factory.openSource();
        PoseEngine<T> engine = null;
        try {
            checkCancelled(cancellation);
            long duration = source.durationMs();
            int interval = sampleIntervalMs(duration);
            int total = (int) ((duration - 1) / interval + 1);
            // 恢复旧的连续跟踪；仅深蹲疑似冻结时尝试一个新的 STREAM。
            engine = factory.openEngine(false);
            checkCancelled(cancellation);

            List<PoseFrame> rawFrames = new ArrayList<>(total);
            List<PoseFrame> filteredFrames = new ArrayList<>(total);
            List<boolean[]> pendingJumps = new ArrayList<>(total);
            PoseSmoother smoother = new PoseSmoother();
            FrozenPoseRecovery recovery = session.getExerciseType() == ExerciseType.SQUAT
                    ? new FrozenPoseRecovery() : null;
            int sourceWidth = 0;
            int sourceHeight = 0;
            int missing = 0;
            for (int i = 0; i < total; i++) {
                checkCancelled(cancellation);
                long timeMs = (long) i * interval;
                ImageFrame<T> image = source.frameAt(timeMs);
                PoseFrame raw;
                int width = sourceWidth;
                int height = sourceHeight;
                if (image == null) {
                    missing++;
                    raw = PoseFrame.empty(timeMs);
                    if (recovery != null) recovery.observe(raw, width, height);
                } else {
                    try {
                        checkCancelled(cancellation);
                        width = image.width();
                        height = image.height();
                        if (width <= 0 || height <= 0) throw new IOException("视频画面尺寸无效");
                        if (sourceWidth == 0) {
                            sourceWidth = width;
                            sourceHeight = height;
                        } else {
                            double oldAspect = sourceWidth / (double) sourceHeight;
                            double aspect = width / (double) height;
                            if (Math.abs(aspect / oldAspect - 1d) > 0.01d) {
                                throw new IOException("视频画面比例中途变化，无法可靠叠加骨架");
                            }
                        }
                        raw = engine.detect(image, timeMs);
                        if (raw == null || raw.getTimestampMs() != timeMs) {
                            throw new IOException("视频识别结果时间不匹配");
                        }
                        if (recovery != null && recovery.observe(raw, width, height)) {
                            PoseEngine<T> candidate = null;
                            try {
                                checkCancelled(cancellation);
                                candidate = factory.openEngine(false);
                                PoseFrame fresh = candidate.detect(image, timeMs);
                                checkCancelled(cancellation);
                                if (recovery.accepts(raw, fresh, width, height)) {
                                    PoseEngine<T> previous = engine;
                                    engine = candidate;
                                    candidate = null;
                                    raw = fresh;
                                    previous.close();
                                }
                            } catch (Cancelled cancelled) {
                                throw cancelled;
                            } catch (Exception ignored) {
                                // 候选不可用时保留原先的跟踪结果，不中断回放。
                            } finally {
                                if (candidate != null) candidate.close();
                            }
                        }
                    } finally {
                        image.close();
                    }
                }
                checkCancelled(cancellation);
                // 没有可对齐视频的历史重力时间戳，重识别判定使用视频自身坐标。
                PoseFrame bounded = raw.maskOutOfFrame(Math.max(1, width), Math.max(1, height));
                PoseFrame filtered = smoother.smooth(bounded);
                rawFrames.add(raw);
                filteredFrames.add(filtered);
                pendingJumps.add(smoother.getPendingJumps());
                if (progress != null) progress.onProgress(i + 1, total);
            }
            checkCancelled(cancellation);
            if (sourceWidth == 0) throw new IOException("无法从录像中提取画面");
            ReplayTimeline timeline = ReplayTimeline.buildForVideo(filteredFrames, session);
            checkCancelled(cancellation);
            return new VideoPoseReanalysis(rawFrames, filteredFrames, pendingJumps, timeline,
                    sourceWidth, sourceHeight, duration, interval, missing);
        } finally {
            try {
                if (engine != null) engine.close();
            } finally {
                source.close();
            }
        }
    }
}
