package com.zxw.fitness.model;

/**
 * 支持的动作类型。
 *
 * <p>每个动作都带有：中文名称、判定要点说明、结果单位（次/秒）以及历史记录中使用的存储 key。
 * 存储 key 一旦确定就不要修改，否则老的历史记录会读不到。</p>
 */
public enum ExerciseType {

    /** 倒立（自由倒立）：统计坚持时间。 */
    HANDSTAND("handstand", "倒立", "秒", Unit.SECONDS,
            "身体离地、躯干倒置并保持稳定；脚落地或身体歪斜即结束计时。",
            true, false),

    /** 靠墙倒立：统计坚持时间。 */
    WALL_HANDSTAND("wall_handstand", "靠墙倒立", "秒", Unit.SECONDS,
            "背/脚靠墙，躯干倒置；离开墙面或脚落地即结束计时。",
            true, true),

    /** 折凳俯卧撑：双手撑凳（上斜）或双脚垫凳（下斜），统计次数。 */
    CHAIR_PUSHUP("chair_pushup", "折凳俯卧撑", "次", Unit.COUNT,
            "双手撑折凳（上斜）或双脚垫折凳（下斜）；胸口下放到与凳面接近再撑起，身体保持一条直线。",
            false, false),

    /** 标准俯卧撑：统计次数。 */
    PUSHUP("pushup", "俯卧撑", "次", Unit.COUNT,
            "手掌在肩下、身体一条直线；胸口下降到肘部约 90° 再撑起。",
            false, false),

    /** 不标准俯卧撑：统计"不标准次数"（幅度不足、塌腰/撅臀、跪姿等）。 */
    BAD_PUSHUP("bad_pushup", "不标准俯卧撑", "次", Unit.COUNT,
            "身体未保持一条直线（塌腰/撅臀）或下降幅度不足，单独计数提醒纠正。",
            false, false),

    /** 平板支撑：统计坚持时间，塌腰时不计时。 */
    PLANK("plank", "平板支撑", "秒", Unit.SECONDS,
            "小臂撑在肩下、身体肩-髋-踝成一条直线；塌腰或髋部下坠时暂停计时。",
            true, false),

    /** 深蹲：统计次数，髋部下蹲深度与躯干前倾都参与判定。 */
    SQUAT("squat", "深蹲", "次", Unit.COUNT,
            "站直→下蹲（大腿接近水平）→起立；只弯膝不坐髋、或上身前倾过多会记为不标准。",
            false, false),

    /** 引体向上：需要画面同时拍到横杆与双手。 */
    PULLUP("pullup", "引体向上", "次", Unit.COUNT,
            "悬垂后拉到下巴接近横杆再放下；只弯手臂、身体没上去不计数。需要镜头拍到双手与全身。",
            false, false),

    /** 靠墙蹲：背靠墙屈膝坐到大腿水平，统计坚持时间。 */
    WALL_SIT("wall_sit", "靠墙蹲", "秒", Unit.SECONDS,
            "背靠墙下蹲到大腿接近水平（膝角约 90°）并保持；站起来或大腿抬高即结束计时。",
            true, true),

    /** 跑步计步：用左右脚交替抬起统计步数。 */
    RUNNING("running", "跑步计步", "步", Unit.COUNT,
            "原地跑或原地踏步，全身入镜；按左右脚交替抬起统计步数与步频。",
            false, false);

    public enum Unit {
        /** 计数（次） */
        COUNT,
        /** 计时（秒） */
        SECONDS
    }

    private final String key;
    private final String title;
    private final String unitLabel;
    private final Unit unit;
    private final String description;
    private final boolean timer;
    private final boolean wall;

    ExerciseType(String key, String title, String unitLabel, Unit unit,
                 String description, boolean timer, boolean wall) {
        this.key = key;
        this.title = title;
        this.unitLabel = unitLabel;
        this.unit = unit;
        this.description = description;
        this.timer = timer;
        this.wall = wall;
    }

    /** 历史记录中持久化的 key。 */
    public String getKey() {
        return key;
    }

    public String getTitle() {
        return title;
    }

    /** 结果单位文案，"秒" 或 "次"。 */
    public String getUnitLabel() {
        return unitLabel;
    }

    public Unit getUnit() {
        return unit;
    }

    public String getDescription() {
        return description;
    }

    /** true 表示该类动作以"坚持时间"为结果，false 表示以"个数"为结果。 */
    public boolean isTimerExercise() {
        return timer;
    }

    /** 支持以一次往返的个人幅度作为目标；计时和步数动作暂不使用此校准。 */
    public boolean supportsPersonalCalibration() {
        return this == PUSHUP || this == CHAIR_PUSHUP || this == BAD_PUSHUP
                || this == SQUAT || this == PULLUP;
    }

    /** 是否为靠墙类动作（需要额外的墙面判定）。 */
    public boolean isWallExercise() {
        return wall;
    }

    public static ExerciseType fromKey(String key) {
        if (key != null) {
            for (ExerciseType type : values()) {
                if (type.key.equals(key)) {
                    return type;
                }
            }
        }
        return PUSHUP;
    }
}
