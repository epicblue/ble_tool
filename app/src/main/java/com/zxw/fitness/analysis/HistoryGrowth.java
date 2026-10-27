package com.zxw.fitness.analysis;

import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.Session;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * 儿童友好的历史摘要。只读取当前账号已有的记录，不持久化奖励或修改训练成绩。
 * 日期按传入时区的自然日计算；无效/未来的时间不计入天数。
 */
public final class HistoryGrowth {
    public static final int RECENT_DAYS = 7;

    public static final class Day {
        private final int month;
        private final int dayOfMonth;
        private final int dayOfWeek;
        private final boolean today;
        private final boolean recorded;

        private Day(Calendar date, boolean today, boolean recorded) {
            this.month = date.get(Calendar.MONTH) + 1;
            this.dayOfMonth = date.get(Calendar.DAY_OF_MONTH);
            this.dayOfWeek = date.get(Calendar.DAY_OF_WEEK);
            this.today = today;
            this.recorded = recorded;
        }

        public int getMonth() { return month; }
        public int getDayOfMonth() { return dayOfMonth; }
        /** Calendar.SUNDAY 到 Calendar.SATURDAY。 */
        public int getDayOfWeek() { return dayOfWeek; }
        public boolean isToday() { return today; }
        public boolean hasRecord() { return recorded; }
    }

    public static final class Badge {
        private final String icon;
        private final String title;
        private final String description;
        private final int progress;
        private final int goal;

        private Badge(String icon, String title, String description, int progress, int goal) {
            this.icon = icon;
            this.title = title;
            this.description = description;
            this.progress = Math.min(progress, goal);
            this.goal = goal;
        }

        public String getIcon() { return icon; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public int getProgress() { return progress; }
        public int getGoal() { return goal; }
        public boolean isUnlocked() { return progress >= goal; }
    }

    private final int recordCount;
    private final int activeDays;
    private final int exerciseTypes;
    private final int recentActiveDays;
    private final List<Day> recentDays;
    private final List<Badge> badges;

    private HistoryGrowth(int recordCount, int activeDays, int exerciseTypes,
                          int recentActiveDays, List<Day> recentDays, List<Badge> badges) {
        this.recordCount = recordCount;
        this.activeDays = activeDays;
        this.exerciseTypes = exerciseTypes;
        this.recentActiveDays = recentActiveDays;
        this.recentDays = Collections.unmodifiableList(recentDays);
        this.badges = Collections.unmodifiableList(badges);
    }

    /** 列表应仅包含当前账号的记录；筛选动作不会改变整份历史摘要。 */
    public static HistoryGrowth from(List<Session> sessions, long nowMs, TimeZone timeZone) {
        TimeZone zone = timeZone == null ? TimeZone.getDefault() : timeZone;
        Set<String> dates = new HashSet<>();
        Set<String> types = new HashSet<>();
        Calendar recordDate = Calendar.getInstance(zone, Locale.CHINA);
        int count = 0;
        if (sessions != null) {
            for (Session session : sessions) {
                if (session == null) continue;
                count++;
                String key = session.getExerciseKey();
                ExerciseType type = ExerciseType.findByKey(key);
                if (type != null) types.add(type.catalogType().getKey());
                long time = session.getStartedAt();
                if (time > 0 && time <= nowMs) {
                    recordDate.setTimeInMillis(time);
                    dates.add(dateKey(recordDate));
                }
            }
        }

        Calendar current = Calendar.getInstance(zone, Locale.CHINA);
        current.setTimeInMillis(nowMs);
        List<Day> days = new ArrayList<>(RECENT_DAYS);
        int recent = 0;
        for (int offset = RECENT_DAYS - 1; offset >= 0; offset--) {
            Calendar date = (Calendar) current.clone();
            // Calendar 而不是固定的 24 小时：跨夏令时/时区偏移时仍是本地自然日。
            date.add(Calendar.DAY_OF_YEAR, -offset);
            boolean recorded = dates.contains(dateKey(date));
            if (recorded) recent++;
            days.add(new Day(date, offset == 0, recorded));
        }

        List<Badge> badges = new ArrayList<>(3);
        badges.add(new Badge("🚀", "星球启航", "保存 1 条运动记录", count, 1));
        badges.add(new Badge("🌈", "花样探索", "尝试并保存 3 种不同动作", types.size(), 3));
        badges.add(new Badge("⭐", "闪亮足迹", "在 5 个不同的日子留下记录", dates.size(), 5));
        return new HistoryGrowth(count, dates.size(), types.size(), recent, days, badges);
    }

    private static String dateKey(Calendar date) {
        return date.get(Calendar.ERA) + ":" + date.get(Calendar.YEAR) + ":"
                + date.get(Calendar.DAY_OF_YEAR);
    }

    public int getRecordCount() { return recordCount; }
    public int getActiveDays() { return activeDays; }
    public int getExerciseTypes() { return exerciseTypes; }
    public int getRecentActiveDays() { return recentActiveDays; }
    public List<Day> getRecentDays() { return recentDays; }
    public List<Badge> getBadges() { return badges; }

    public int getUnlockedBadgeCount() {
        int count = 0;
        for (Badge badge : badges) if (badge.isUnlocked()) count++;
        return count;
    }
}
