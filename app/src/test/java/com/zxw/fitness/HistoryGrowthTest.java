package com.zxw.fitness;

import com.zxw.fitness.analysis.HistoryGrowth;
import com.zxw.fitness.analysis.HistoryGrowth.Day;
import com.zxw.fitness.model.ExerciseType;
import com.zxw.fitness.model.Session;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** 成长星球只根据现有记录生成，不依赖 Android、当前系统日期或连续打卡。 */
public class HistoryGrowthTest {
    private static final TimeZone SHANGHAI = TimeZone.getTimeZone("Asia/Shanghai");
    private static final TimeZone LOS_ANGELES = TimeZone.getTimeZone("America/Los_Angeles");

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static Session session(ExerciseType type, long timestamp) {
        Session session = new Session();
        session.setUserId("child");
        session.setExerciseType(type);
        session.setStartedAt(timestamp);
        session.setReps(7);
        return session;
    }

    @Test public void emptyHistoryShowsNoImaginaryStarsOrBadges() {
        long now = at(SHANGHAI, 2024, 6, 3, 12, 0);
        HistoryGrowth summary = HistoryGrowth.from(null, now, SHANGHAI);
        assertEquals(0, summary.getRecordCount());
        assertEquals(0, summary.getActiveDays());
        assertEquals(0, summary.getExerciseTypes());
        assertEquals(0, summary.getRecentActiveDays());
        assertEquals(0, summary.getUnlockedBadgeCount());
        assertEquals(7, summary.getRecentDays().size());
        for (Day day : summary.getRecentDays()) assertFalse(day.hasRecord());
        assertTrue(summary.getRecentDays().get(6).isToday());
        assertEquals(3, summary.getBadges().size());
        assertEquals(0, summary.getBadges().get(0).getProgress());
    }

    @Test public void multipleRecordsOnOneDayAndChairVariantsDoNotInflateDaysOrKinds() {
        long now = at(SHANGHAI, 2024, 6, 3, 20, 0);
        Session hands = session(ExerciseType.CHAIR_PUSHUP, at(SHANGHAI, 2024, 6, 3, 10, 0));
        hands.setVariant("hands");
        Session feet = session(ExerciseType.CHAIR_PUSHUP, at(SHANGHAI, 2024, 6, 3, 11, 0));
        feet.setVariant("feet");
        List<Session> sessions = Arrays.asList(hands, feet,
                session(ExerciseType.SQUAT, at(SHANGHAI, 2024, 6, 3, 12, 0)),
                session(ExerciseType.RUNNING, at(SHANGHAI, 2024, 6, 3, 13, 0)));
        HistoryGrowth summary = HistoryGrowth.from(sessions, now, SHANGHAI);
        assertEquals(4, summary.getRecordCount());
        assertEquals(1, summary.getActiveDays());
        assertEquals(1, summary.getRecentActiveDays());
        assertEquals(3, summary.getExerciseTypes());
        assertEquals(2, summary.getUnlockedBadgeCount());
        assertTrue(summary.getBadges().get(0).isUnlocked());
        assertTrue(summary.getBadges().get(1).isUnlocked());
        assertFalse(summary.getBadges().get(2).isUnlocked());
        assertEquals(1, summary.getBadges().get(2).getProgress());
        assertTrue(summary.getRecentDays().get(6).hasRecord());
    }

    @Test public void milestonesAccumulateAcrossYearsAndNeedNotBeConsecutive() {
        long now = at(SHANGHAI, 2025, 1, 8, 12, 0);
        List<Session> sessions = Arrays.asList(
                session(ExerciseType.SQUAT, at(SHANGHAI, 2024, 12, 1, 9, 0)),
                session(ExerciseType.RUNNING, at(SHANGHAI, 2024, 12, 10, 9, 0)),
                session(ExerciseType.PLANK, at(SHANGHAI, 2024, 12, 31, 9, 0)),
                session(ExerciseType.SQUAT, at(SHANGHAI, 2025, 1, 4, 9, 0)),
                session(ExerciseType.RUNNING, at(SHANGHAI, 2025, 1, 8, 9, 0)));
        HistoryGrowth summary = HistoryGrowth.from(sessions, now, SHANGHAI);
        assertEquals(5, summary.getActiveDays());
        assertEquals(2, summary.getRecentActiveDays());
        assertEquals(3, summary.getUnlockedBadgeCount());
        assertTrue(summary.getRecentDays().get(2).hasRecord()); // 1 月 4 日
        assertTrue(summary.getRecentDays().get(6).hasRecord()); // 今天

        // 放一段时间后不清零既有成就，也不要求补打卡。
        HistoryGrowth later = HistoryGrowth.from(sessions, at(SHANGHAI, 2025, 2, 8, 12, 0), SHANGHAI);
        assertEquals(0, later.getRecentActiveDays());
        assertEquals(3, later.getUnlockedBadgeCount());
        assertEquals(5, later.getActiveDays());
    }

    @Test public void naturalDayUsesSuppliedTimeZone() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long first = at(utc, 2024, 2, 2, 15, 59);
        long second = at(utc, 2024, 2, 2, 16, 1);
        long now = at(utc, 2024, 2, 3, 12, 0);
        List<Session> sessions = Arrays.asList(session(ExerciseType.SQUAT, first),
                session(ExerciseType.SQUAT, second));
        assertEquals(2, HistoryGrowth.from(sessions, now, SHANGHAI).getActiveDays());
        assertEquals(1, HistoryGrowth.from(sessions, now, LOS_ANGELES).getActiveDays());
    }

    @Test public void recentCalendarUsesLocalDaysAcrossDaylightSavingChange() {
        long now = at(LOS_ANGELES, 2024, 3, 12, 8, 0);
        List<Session> sessions = Arrays.asList(
                session(ExerciseType.SQUAT, at(LOS_ANGELES, 2024, 3, 9, 23, 30)),
                session(ExerciseType.SQUAT, at(LOS_ANGELES, 2024, 3, 10, 3, 30)),
                session(ExerciseType.SQUAT, at(LOS_ANGELES, 2024, 3, 11, 23, 0)),
                session(ExerciseType.SQUAT, at(LOS_ANGELES, 2024, 3, 12, 7, 0)));
        HistoryGrowth summary = HistoryGrowth.from(sessions, now, LOS_ANGELES);
        assertEquals(4, summary.getActiveDays());
        assertEquals(4, summary.getRecentActiveDays());
        assertEquals(6, summary.getRecentDays().get(0).getDayOfMonth());
        for (int i = 3; i < 7; i++) assertTrue(summary.getRecentDays().get(i).hasRecord());
        assertEquals(Calendar.TUESDAY, summary.getRecentDays().get(6).getDayOfWeek());
    }

    @Test public void repeatedClockHourAtFallDstChangeStillCountsOneLocalDay() {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long beforeClockChange = at(utc, 2024, 11, 3, 8, 30);
        long afterClockChange = at(utc, 2024, 11, 3, 9, 30);
        long now = at(LOS_ANGELES, 2024, 11, 4, 12, 0);
        HistoryGrowth summary = HistoryGrowth.from(Arrays.asList(
                session(ExerciseType.SQUAT, beforeClockChange),
                session(ExerciseType.RUNNING, afterClockChange)), now, LOS_ANGELES);
        assertEquals(2, summary.getRecordCount());
        assertEquals(1, summary.getActiveDays());
        assertEquals(1, summary.getRecentActiveDays());
        assertTrue(summary.getRecentDays().get(5).hasRecord());
    }

    @Test public void unknownOrFutureDatesDoNotInventActiveDaysAndUnknownExerciseIsNotAType() {
        long now = at(SHANGHAI, 2024, 6, 3, 12, 0);
        Session unknown = session(ExerciseType.SQUAT, 0);
        unknown.setExerciseKey("unknown-from-a-newer-version");
        Session future = session(ExerciseType.RUNNING, at(SHANGHAI, 2024, 6, 4, 12, 0));
        Session invalid = session(ExerciseType.SQUAT, -1);
        Session valid = session(ExerciseType.SQUAT, now);
        HistoryGrowth summary = HistoryGrowth.from(Arrays.asList(null, unknown, future, invalid, valid), now, SHANGHAI);
        assertEquals(4, summary.getRecordCount());
        assertEquals(1, summary.getActiveDays());
        assertEquals(1, summary.getRecentActiveDays());
        assertEquals(2, summary.getExerciseTypes());
    }

    @Test public void laterTodayIsNotLitPrematurelyWhenDeviceClockIsWrong() {
        long now = at(SHANGHAI, 2024, 6, 3, 12, 0);
        Session futureToday = session(ExerciseType.SQUAT, now + 1000);
        HistoryGrowth summary = HistoryGrowth.from(Collections.singletonList(futureToday), now, SHANGHAI);
        assertEquals(1, summary.getRecordCount());
        assertEquals(0, summary.getActiveDays());
        assertFalse(summary.getRecentDays().get(6).hasRecord());
    }

    @Test public void deletingARecordRecomputesProgressWithoutMutatingStoredResults() {
        long now = at(SHANGHAI, 2024, 6, 3, 12, 0);
        Session saved = session(ExerciseType.SQUAT, at(SHANGHAI, 2024, 6, 3, 10, 0));
        saved.setPoseFile("original.dat");
        saved.setVideoFile("original.mp4");
        List<Session> records = new ArrayList<>(Collections.singletonList(saved));
        HistoryGrowth before = HistoryGrowth.from(records, now, SHANGHAI);
        assertEquals(1, before.getUnlockedBadgeCount());
        records.clear();
        HistoryGrowth after = HistoryGrowth.from(records, now, SHANGHAI);
        assertEquals(0, after.getUnlockedBadgeCount());
        assertEquals(0, after.getActiveDays());
        assertEquals(1, before.getRecordCount()); // 已生成的摘要是快照
        assertEquals(7, saved.getReps());
        assertEquals("original.dat", saved.getPoseFile());
        assertEquals("original.mp4", saved.getVideoFile());
    }

    @Test public void badgeProgressIsCappedAndCollectionsAreReadOnly() {
        long now = at(SHANGHAI, 2024, 6, 15, 12, 0);
        List<Session> sessions = new ArrayList<>();
        ExerciseType[] types = {ExerciseType.SQUAT, ExerciseType.PUSHUP,
                ExerciseType.RUNNING, ExerciseType.PLANK};
        for (int day = 1; day <= 10; day++) {
            sessions.add(session(types[day % types.length], at(SHANGHAI, 2024, 6, day, 10, 0)));
        }
        HistoryGrowth summary = HistoryGrowth.from(sessions, now, SHANGHAI);
        assertEquals(1, summary.getBadges().get(0).getProgress());
        assertEquals(3, summary.getBadges().get(1).getProgress());
        assertEquals(5, summary.getBadges().get(2).getProgress());
        assertEquals(3, summary.getUnlockedBadgeCount());
        try {
            summary.getBadges().clear();
            fail("徽章列表应只读");
        } catch (UnsupportedOperationException expected) { }
        try {
            summary.getRecentDays().clear();
            fail("日期列表应只读");
        } catch (UnsupportedOperationException expected) { }
    }
}
