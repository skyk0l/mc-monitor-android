package com.mcmonitor.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent appearance and information options for home-screen widgets. */
public final class WidgetSettingsStore {

    private static final String PREFS = "widget_settings";
    private static final String KEY_ACCENT = "accent";
    private static final String KEY_BACKGROUND = "background";
    private static final String KEY_SHOW_NAME = "show_name";
    private static final String KEY_SHOW_PLAYERS = "show_players";
    private static final String KEY_SHOW_VERSION = "show_version";
    private static final String KEY_SHOW_UPDATED = "show_updated";

    public static final int DEFAULT_ACCENT = 0xFFA7C6A4;
    public static final int[] ACCENTS = {
            0xFFA7C6A4, 0xFF8DBBFF, 0xFFC1A0FF, 0xFFFF9DB5, 0xFFFFC978
    };
    public static final String[] ACCENT_NAMES = {
            "Мятный", "Голубой", "Лавандовый", "Розовый", "Янтарный"
    };
    public static final int[] BACKGROUNDS = {
            0xFF151B1B, 0xFF172235, 0xFF251D32, 0xFF30231B
    };
    public static final String[] BACKGROUND_NAMES = {
            "Графит", "Синий", "Фиолетовый", "Тёплый"
    };

    private WidgetSettingsStore() {
    }

    public static int accent(Context context) {
        return prefs(context).getInt(KEY_ACCENT, DEFAULT_ACCENT);
    }

    public static void setAccent(Context context, int color) {
        prefs(context).edit().putInt(KEY_ACCENT, color).apply();
    }

    public static int background(Context context) {
        return prefs(context).getInt(KEY_BACKGROUND, 0);
    }

    public static void setBackground(Context context, int index) {
        prefs(context).edit().putInt(KEY_BACKGROUND, index).apply();
    }

    public static boolean showName(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_NAME, true);
    }

    public static void setShowName(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_SHOW_NAME, value).apply();
    }

    public static boolean showPlayers(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_PLAYERS, true);
    }

    public static void setShowPlayers(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_SHOW_PLAYERS, value).apply();
    }

    public static boolean showVersion(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_VERSION, true);
    }

    public static void setShowVersion(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_SHOW_VERSION, value).apply();
    }

    public static boolean showUpdated(Context context) {
        return prefs(context).getBoolean(KEY_SHOW_UPDATED, true);
    }

    public static void setShowUpdated(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_SHOW_UPDATED, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
