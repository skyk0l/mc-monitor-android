package com.mcmonitor.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Local notification schedule and do-not-disturb policy. */
public final class NotificationPolicy {
    private static final String PREFERENCES = "app_settings";
    private static final String START_MINUTE = "notification_start_minute";
    private static final String END_MINUTE = "notification_end_minute";
    private static final String DND_UNTIL = "notification_dnd_until";

    private NotificationPolicy() {
    }

    public static int startMinute(Context context) {
        return preferences(context).getInt(START_MINUTE, 0);
    }

    public static int endMinute(Context context) {
        return preferences(context).getInt(END_MINUTE, 1440);
    }

    public static void setStartMinute(Context context, int minute) {
        preferences(context).edit().putInt(START_MINUTE, clamp(minute, 0, 1439)).apply();
    }

    public static void setEndMinute(Context context, int minute) {
        preferences(context).edit().putInt(END_MINUTE, clamp(minute, 0, 1440)).apply();
    }

    public static long dndUntil(Context context) {
        return preferences(context).getLong(DND_UNTIL, 0);
    }

    public static void setDndDuration(Context context, int durationSeconds) {
        long until = durationSeconds == -1
                ? -1
                : durationSeconds <= 0
                ? 0
                : System.currentTimeMillis() + durationSeconds * 1000L;
        preferences(context).edit().putLong(DND_UNTIL, until).apply();
    }

    public static boolean isDndActive(Context context, long nowMillis) {
        long until = dndUntil(context);
        return until == -1 || (until > 0 && nowMillis < until);
    }

    public static boolean notificationsAllowed(Context context, long nowMillis) {
        if (isDndActive(context, nowMillis)) {
            return false;
        }
        java.util.Calendar local = java.util.Calendar.getInstance();
        local.setTimeInMillis(nowMillis);
        int minute = local.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + local.get(java.util.Calendar.MINUTE);
        int start = clamp(startMinute(context), 0, 1439);
        int end = clamp(endMinute(context), 0, 1440);
        if (start == end) {
            return false;
        }
        return start < end
                ? minute >= start && minute < end
                : minute >= start || minute < end;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
