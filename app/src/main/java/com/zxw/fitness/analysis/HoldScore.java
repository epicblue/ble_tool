package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;

/** 训练和回放使用一致的计时展示口径；存档的最长单组仍保持原有含义。 */
public final class HoldScore {
    private HoldScore() { }

    public static long displayMs(ExerciseType type, HoldAnalyzer hold) {
        if (hold == null) return 0;
        return type == ExerciseType.WALL_SIT ? hold.getTotalHoldMs() : hold.getBestHoldMs();
    }

    public static String unitLabel(ExerciseType type) {
        return type == ExerciseType.WALL_SIT ? "秒（累计）" : type.getUnitLabel();
    }
}
