package com.zxw.fitness.camera;

import org.junit.Test;

import static org.junit.Assert.*;

/** 纯 JVM 测试：丢弃旧代的 surface 请求，并等待新 surface 和 STREAMING。 */
public class PreviewSessionGateTest {
    @Test public void waitsForCurrentSurfaceAndStreaming() {
        PreviewSessionGate gate = new PreviewSessionGate();
        gate.begin(1);
        assertFalse(gate.canMap(1));
        gate.onStreamState(true); // 旧预览的状态不能让新相机提前就绪
        assertFalse(gate.canMap(1));
        assertFalse(gate.onSurfaceRequested(0));
        assertFalse(gate.canMap(1));
        assertTrue(gate.onSurfaceRequested(1));
        assertFalse(gate.canMap(1));
        gate.onStreamState(true);
        assertTrue(gate.canMap(1));
        assertFalse(gate.canMap(0));
    }

    @Test public void switchingAndEndingRejectDelayedRequests() {
        PreviewSessionGate gate = new PreviewSessionGate();
        gate.begin(8);
        assertTrue(gate.onSurfaceRequested(8));
        gate.onStreamState(true);
        assertTrue(gate.canMap(8));
        gate.end();
        assertFalse(gate.canMap(8));
        gate.begin(9);
        assertFalse(gate.onSurfaceRequested(8));
        gate.onStreamState(true);
        assertFalse(gate.canMap(9));
        assertTrue(gate.onSurfaceRequested(9));
        gate.onStreamState(true);
        assertTrue(gate.canMap(9));
        assertFalse(gate.canMap(8));
        gate.end();
        assertFalse(gate.onSurfaceRequested(9));
        assertFalse(gate.canMap(9));
    }

    @Test public void replacementSurfaceAndStoppedPreviewRequireNewStreamingState() {
        PreviewSessionGate gate = new PreviewSessionGate();
        gate.begin(2);
        gate.onSurfaceRequested(2);
        gate.onStreamState(true);
        assertTrue(gate.canMap(2));
        gate.onStreamState(false);
        assertFalse(gate.canMap(2));
        gate.onStreamState(true);
        assertTrue(gate.canMap(2));
        gate.onSurfaceRequested(2); // CameraX 可能重建当前镜头的 surface
        assertFalse(gate.canMap(2));
        gate.onStreamState(true);
        assertTrue(gate.canMap(2));
    }
}
