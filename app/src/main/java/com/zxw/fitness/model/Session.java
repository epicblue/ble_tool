package com.zxw.fitness.model;

import java.util.Locale;

/**
 * 一次训练的汇总结果。
 */
public class Session {

    /** 计数类动作的结果字段。 */
    private long id;
    private String userId;
    private String exerciseKey;
    private String variant = "";
    private long startedAt;
    private long durationMs;
    private int reps;
    private int badReps;
    private int formErrors;
    private long holdMs;
    private boolean wallContact;
    private String poseFile;
    private String videoFile;
    private String thumbFile;
    private String note = "";
    /** 计时类动作的稳定度 0~1，无效时为 NaN（不参与 JSON）。 */
    private float stability = Float.NaN;

    public Session() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public ExerciseType getExerciseType() {
        return ExerciseType.fromKey(exerciseKey);
    }

    public void setExerciseType(ExerciseType type) {
        this.exerciseKey = type == null ? ExerciseType.PUSHUP.getKey() : type.getKey();
    }

    public String getExerciseKey() {
        return exerciseKey;
    }

    public void setExerciseKey(String exerciseKey) {
        this.exerciseKey = exerciseKey;
    }

    /** 折凳俯卧撑的变体："hands"=手撑凳，其余为脚垫凳。 */
    public String getVariant() {
        return variant == null ? "" : variant;
    }

    public void setVariant(String variant) {
        this.variant = variant == null ? "" : variant;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    /** 有效次数（计时类动作为 0）。 */
    public int getReps() {
        return reps;
    }

    public void setReps(int reps) {
        this.reps = reps;
    }

    /** 不标准次数（仅俯卧撑类使用）。 */
    public int getBadReps() {
        return badReps;
    }

    public void setBadReps(int badReps) {
        this.badReps = badReps;
    }

    /** 被记录下来的姿势问题次数（塌腰、幅度不足等）。 */
    public int getFormErrors() {
        return formErrors;
    }

    public void setFormErrors(int formErrors) {
        this.formErrors = formErrors;
    }

    /** 计时类动作保持的最长时间（毫秒）。 */
    public long getHoldMs() {
        return holdMs;
    }

    public void setHoldMs(long holdMs) {
        this.holdMs = holdMs;
    }

    /** 靠墙倒立是否检测到贴墙。 */
    public boolean isWallContact() {
        return wallContact;
    }

    public void setWallContact(boolean wallContact) {
        this.wallContact = wallContact;
    }

    public String getPoseFile() {
        return poseFile;
    }

    public void setPoseFile(String poseFile) {
        this.poseFile = poseFile;
    }

    public String getVideoFile() {
        return videoFile;
    }

    public void setVideoFile(String videoFile) {
        this.videoFile = videoFile;
    }

    public String getThumbFile() {
        return thumbFile;
    }

    public void setThumbFile(String thumbFile) {
        this.thumbFile = thumbFile;
    }

    /** 计时类动作的稳定度（0~1）；无数据时返回 NaN。 */
    public float getStability() {
        return stability;
    }

    public void setStability(float stability) {
        this.stability = stability;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note == null ? "" : note;
    }

    /**
     * 结果数值：计时类返回秒数；"不标准俯卧撑"返回不标准次数；其余返回有效次数。
     */
    public double getResultValue() {
        ExerciseType type = getExerciseType();
        if (type.isTimerExercise()) {
            return holdMs / 1000.0;
        }
        if (type == ExerciseType.BAD_PUSHUP) {
            return badReps;
        }
        return reps;
    }

    /** 用于列表展示的结果文案，例如 "12 次"、"48.6 秒"、"86 步"。 */
    public String getResultText() {
        ExerciseType type = getExerciseType();
        if (type.isTimerExercise()) {
            return String.format(Locale.US, "%.1f 秒", holdMs / 1000.0);
        }
        int value = type == ExerciseType.BAD_PUSHUP ? badReps : reps;
        return value + " " + type.getUnitLabel();
    }

    public boolean hasPoseReplay() {
        return poseFile != null && poseFile.length() > 0;
    }

    public boolean hasVideoReplay() {
        return videoFile != null && videoFile.length() > 0;
    }
}
