package com.zxw.fitness.model;

import java.util.Locale;

/**
 * 一次主动确认的个人动作目标。数值是识别器使用的相对量，而不是医学上的标准姿势。
 * 不同账号、动作和折凳变体各自保存；实例不可变，历史记录可安全地保存快照。
 */
public final class PersonalCalibration {
    public static final float MIN_TARGET = 0.15f;
    public static final float MAX_TARGET = 5f;

    private final ExerciseType type;
    private final String variant;
    private final float targetDepth;
    private final float startingAngle;
    private final float capturedAngle;
    private final float capturedMovement;
    private final float maxForm;
    private final float minHipAngle;
    private final float minMovement;
    private final boolean allowKneeling;
    private final boolean formWarning;

    public PersonalCalibration(ExerciseType type, String variant, float targetDepth,
                               float capturedAngle, float capturedMovement, float maxForm,
                               float minHipAngle, float minMovement, boolean allowKneeling,
                               boolean formWarning) {
        this(type, variant, targetDepth, capturedAngle, capturedMovement, maxForm,
                minHipAngle, minMovement, allowKneeling, formWarning, defaultStartingAngle(type));
    }

    public PersonalCalibration(ExerciseType type, String variant, float targetDepth,
                               float capturedAngle, float capturedMovement, float maxForm,
                               float minHipAngle, float minMovement, boolean allowKneeling,
                               boolean formWarning, float startingAngle) {
        this.type = type;
        this.variant = normalizedVariant(type, variant);
        this.targetDepth = targetDepth;
        this.startingAngle = startingAngle;
        this.capturedAngle = capturedAngle;
        this.capturedMovement = capturedMovement;
        this.maxForm = maxForm;
        this.minHipAngle = minHipAngle;
        this.minMovement = minMovement;
        this.allowKneeling = allowKneeling;
        this.formWarning = formWarning;
    }

    public ExerciseType getType() { return type; }
    public String getVariant() { return variant; }
    public float getTargetDepth() { return targetDepth; }
    public float getStartingAngle() { return startingAngle; }
    public float getCapturedAngle() { return capturedAngle; }
    public float getCapturedMovement() { return capturedMovement; }
    /** 俯卧撑为髋偏移比例；深蹲为前倾角；折刀为最大肩髋踝夹角。 */
    public float getMaxForm() { return maxForm; }
    public float getMinHipAngle() { return minHipAngle; }
    /** 深蹲、引体和折刀的最小相对移动量。 */
    public float getMinMovement() { return minMovement; }
    public boolean isKneelingAllowed() { return allowKneeling; }
    public boolean hasFormWarning() { return formWarning; }

    public boolean matches(ExerciseType exercise, String exerciseVariant) {
        return type == exercise && variant.equals(normalizedVariant(exercise, exerciseVariant));
    }

    public String getKey() { return key(type, variant); }

    public static String key(ExerciseType type, String variant) {
        return type == null ? "" : type.getKey() + ":" + normalizedVariant(type, variant);
    }

    public static float defaultStartingAngle(ExerciseType type) {
        return type == ExerciseType.SQUAT ? 155f
                : type == ExerciseType.PULLUP || type == ExerciseType.PIKE_PUSHUP ? 150f : 152f;
    }

    /** 极限起始角小于标准到位角时仍留出有意义的行程参考。 */
    public static float referenceAngle(float startingAngle, float standardDepthAngle) {
        return Math.min(standardDepthAngle, startingAngle - 20f);
    }

    public static String normalizedVariant(ExerciseType type, String variant) {
        if (type == ExerciseType.CHAIR_PUSHUP) {
            return "hands".equals(variant) ? "hands" : "feet";
        }
        return "";
    }

    /** 损坏的设置不可用于放宽动作判定。 */
    public boolean isValid() {
        return type != null && type.supportsPersonalCalibration()
                && finite(targetDepth) && targetDepth >= MIN_TARGET && targetDepth <= MAX_TARGET
                && finite(startingAngle) && startingAngle >= 20f && startingAngle <= 180f
                && finite(capturedAngle) && capturedAngle >= 0f && capturedAngle <= 180f
                && finite(capturedMovement) && capturedMovement >= 0f && capturedMovement <= 10f
                && finite(maxForm) && maxForm >= 0f && maxForm <= 180f
                && finite(minHipAngle) && minHipAngle >= 0f && minHipAngle <= 180f
                && finite(minMovement) && minMovement >= 0f && minMovement <= 10f
                && (type != ExerciseType.PIKE_PUSHUP || (!allowKneeling
                && maxForm >= 145f && maxForm <= 165f
                && minMovement >= 0.02f && minMovement <= 0.10f
                && capturedMovement >= 0.025f));
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public String getTitle() {
        if (type == ExerciseType.CHAIR_PUSHUP) {
            return type.getTitle() + ("hands".equals(variant) ? " · 手撑凳" : " · 脚垫凳");
        }
        return type == null ? "未知动作" : type.historyTitle();
    }

    /** 显示的是已保存的个人目标及采样值，方便查看和重置。 */
    public String describe() {
        String extra = allowKneeling ? " · 允许采样时的跪姿" : "";
        if (formWarning) {
            extra += " · 姿势仍需注意";
        }
        if (type == ExerciseType.SQUAT) {
            return String.format(Locale.CHINA,
                    "起始膝角 %.1f°；膝角≤%.1f°或髋下移≥%.2f倍躯干；最小髋移动 %.2f，前倾≤%.1f°。"
                            + "\n采样：膝角 %.1f°，髋下移 %.2f倍%s",
                    startingAngle, Math.max(0f, startingAngle - (startingAngle
                            - referenceAngle(startingAngle, 100f)) * targetDepth),
                    0.35f * targetDepth, minMovement, maxForm, capturedAngle, capturedMovement, extra);
        }
        if (type == ExerciseType.PULLUP) {
            return String.format(Locale.CHINA,
                    "起始肘角 %.1f°；肘角≤%.1f°，身体至少上升 %.2f 倍躯干。"
                            + "\n采样：肘角 %.1f°，身体上升 %.2f倍%s",
                    startingAngle, Math.max(0f, startingAngle - (startingAngle
                            - referenceAngle(startingAngle, 95f)) * targetDepth),
                    minMovement, capturedAngle, capturedMovement, extra);
        }
        if (type == ExerciseType.PIKE_PUSHUP) {
            return String.format(Locale.CHINA,
                    "起始肘角 %.1f°；肘角≤%.1f°，肩腕相对高度比例下降至少 %.2f，肩髋踝角≤%.1f°。"
                            + "\n采样：肘角 %.1f°，比例下降 %.2f%s",
                    startingAngle, Math.max(0f, startingAngle - (startingAngle
                            - referenceAngle(startingAngle, 100f)) * targetDepth),
                    minMovement, maxForm, capturedAngle, capturedMovement, extra);
        }
        float fullAngle = type == ExerciseType.CHAIR_PUSHUP && "hands".equals(variant)
                ? 106f : 100f;
        return String.format(Locale.CHINA,
                "起始肘角 %.1f°；肘角≤%.1f°或下降进度≥%.0f%%；髋偏移≤%.2f倍躯干，侧面髋角≥%.1f°。"
                        + "\n采样：肘角 %.1f°，下降进度 %.0f%%%s",
                startingAngle, Math.max(0f, startingAngle - (startingAngle
                        - referenceAngle(startingAngle, fullAngle)) * targetDepth),
                85f * targetDepth, maxForm, minHipAngle, capturedAngle, capturedMovement * 100f, extra);
    }
}
