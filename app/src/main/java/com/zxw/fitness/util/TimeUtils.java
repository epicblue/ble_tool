package com.zxw.fitness.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 时间显示工具。
 */
public final class TimeUtils {

    private TimeUtils() {
    }

    private static final SimpleDateFormat DATE_TIME =
            new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
    private static final SimpleDateFormat DATE_ONLY =
            new SimpleDateFormat("yyyy年M月d日", Locale.CHINA);

    public static String formatDateTime(long timestamp) {
        if (timestamp <= 0) {
            return "未知时间";
        }
        return DATE_TIME.format(new Date(timestamp));
    }

    public static String formatDate(long timestamp) {
        if (timestamp <= 0) {
            return "未知时间";
        }
        return DATE_ONLY.format(new Date(timestamp));
    }

    /** "今天 14:20 / 昨天 09:12 / 3月5日 18:00" */
    public static String formatFriendly(long timestamp) {
        if (timestamp <= 0) {
            return "未知时间";
        }
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestamp);
        boolean sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR);
        if (sameDay) {
            return "今天 " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(timestamp));
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        boolean yesterday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR);
        if (yesterday) {
            return "昨天 " + new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(timestamp));
        }
        return DATE_TIME.format(new Date(timestamp));
    }

    /** 秒表格式 mm:ss 或 hh:mm:ss。 */
    public static String formatStopwatch(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    /** "3 分 20 秒" / "48 秒" */
    public static String formatDurationCn(long ms) {
        long totalSeconds = Math.max(0, ms) / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) {
            return minutes + " 分 " + seconds + " 秒";
        }
        return seconds + " 秒";
    }
}
