package com.zxw.fitness.analysis;

import com.zxw.fitness.model.Session;
import com.zxw.fitness.model.ExerciseType;

/**
 * 训练流水线版本（姿态模型/跟踪、坐标处理、滤波、动作判定与校准语义）。
 * 共享流水线或已有动作逻辑改变时须更新相应版本；新增独立动作可使用独立版本，
 * 不必使未改变的旧动作缓存失效。
 */
public final class AlgorithmVersion {
    public static final String CURRENT = "fitness-2026-10-stream1";
    public static final String PIKE_CURRENT = "fitness-2026-10-pike1";

    public static String forExercise(ExerciseType type) {
        return type == ExerciseType.PIKE_PUSHUP ? PIKE_CURRENT : CURRENT;
    }

    private AlgorithmVersion() { }

    public static boolean matches(Session session) {
        return session != null && forExercise(session.getExerciseType()).equals(session.getAlgorithmVersion());
    }
}
