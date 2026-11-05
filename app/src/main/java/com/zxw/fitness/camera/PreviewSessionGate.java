package com.zxw.fitness.camera;

/**
 * 等待当前相机的 surface 请求和 STREAMING 状态后使用预览坐标变换。
 * 所有方法都在主线程调用；代数用于丢弃切镜头/销毁前迟到的 surface 请求。
 * PreviewView 的状态不带代数，旧 STREAMING 若在新请求后到达仍可能被误认。
 */
final class PreviewSessionGate {
    private long generation = Long.MIN_VALUE;
    private boolean surfaceRequested;
    private boolean streaming;

    void begin(long generation) {
        this.generation = generation;
        surfaceRequested = false;
        streaming = false;
    }

    boolean onSurfaceRequested(long generation) {
        if (this.generation != generation) return false;
        surfaceRequested = true;
        streaming = false;
        return true;
    }

    void onStreamState(boolean streaming) {
        this.streaming = surfaceRequested && streaming;
    }

    boolean canMap(long generation) {
        return this.generation == generation && surfaceRequested && streaming;
    }

    void end() {
        generation = Long.MIN_VALUE;
        surfaceRequested = false;
        streaming = false;
    }
}
