package com.zxw.fitness.analysis;

import com.zxw.fitness.model.Session;

/**
 * 训练流水线版本（姿态模型/跟踪、坐标处理、滤波、动作判定与校准语义）。
 * 任何可能改变逐帧结果的修改都必须更换此值，不能把旧缓存冒充当前结果。
 */
public final class AlgorithmVersion {
    public static final String CURRENT = "fitness-2026-10-stream1";

    private AlgorithmVersion() { }

    public static boolean matches(Session session) {
        return session != null && CURRENT.equals(session.getAlgorithmVersion());
    }
}
