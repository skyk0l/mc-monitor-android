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
    public static final float DEFAULT_OPACITY = 1f;
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
        return accent(context, 0);
    }

    public static int accent(Context context, int widgetId) {
        SharedPreferences preferences = prefs(context);
        return preferences.getInt(
                key(widgetId, KEY_ACCENT),
                preferences.getInt(KEY_ACCENT, DEFAULT_ACCENT));
    }

    public static void setAccent(Context context, int color) {
        setAccent(context, 0, color);
    }

    public static void setAccent(Context context, int widgetId, int color) {
        prefs(context).edit().putInt(key(widgetId, KEY_ACCENT), color).apply();
    }

    public static int background(Context context) {
        return background(context, 0);
    }

    public static int background(Context context, int widgetId) {
        SharedPreferences preferences = prefs(context);
        return preferences.getInt(
                key(widgetId, KEY_BACKGROUND),
                preferences.getInt(KEY_BACKGROUND, 0));
    }

    public static void setBackground(Context context, int index) {
        setBackground(context, 0, index);
    }

    public static void setBackground(Context context, int widgetId, int index) {
        prefs(context).edit().putInt(key(widgetId, KEY_BACKGROUND), index).apply();
    }

    public static boolean showName(Context context) {
        return showName(context, 0);
    }

    public static boolean showName(Context context, int widgetId) {
        SharedPreferences preferences = prefs(context);
        return preferences.getBoolean(
                key(widgetId, KEY_SHOW_NAME),
                preferences.getBoolean(KEY_SHOW_NAME, true));
    }

    public static void setShowName(Context context, boolean value) {
        setShowName(context, 0, value);
    }

    public static void setShowName(Context context, int widgetId, boolean value) {
        prefs(context).edit().putBoolean(key(widgetId, KEY_SHOW_NAME), value).apply();
    }

    public static boolean showPlayers(Context context) {
        return showPlayers(context, 0);
    }

    public static boolean showPlayers(Context context, int widgetId) {
        SharedPreferences preferences = prefs(context);
        return preferences.getBoolean(
                key(widgetId, KEY_SHOW_PLAYERS),
                preferences.getBoolean(KEY_SHOW_PLAYERS, true));
    }

    public static void setShowPlayers(Context context, boolean value) {
        setShowPlayers(context, 0, value);
    }

    public static void setShowPlayers(Context context, int widgetId, boolean value) {
        prefs(context).edit().putBoolean(key(widgetId, KEY_SHOW_PLAYERS), value).apply();
    }

    public static boolean showVersion(Context context) {
        return showVersion(context, 0);
    }

    public static boolean showVersion(Context context, int widgetId) {
        SharedPreferences preferences = prefs(context);
        return preferences.getBoolean(
                key(widgetId, KEY_SHOW_VERSION),
                preferences.getBoolean(KEY_SHOW_VERSION, true));
    }

    public static void setShowVersion(Context context, boolean value) {
        setShowVersion(context, 0, value);
    }

    public static void setShowVersion(Context context, int widgetId, boolean value) {
        prefs(context).edit().putBoolean(key(widgetId, KEY_SHOW_VERSION), value).apply();
    }

    public static boolean showUpdated(Context context) {
        return showUpdated(context, 0);
    }

    public static boolean showUpdated(Context context, int widgetId) {
        SharedPreferences preferences = prefs(context);
        return preferences.getBoolean(
                key(widgetId, KEY_SHOW_UPDATED),
                preferences.getBoolean(KEY_SHOW_UPDATED, true));
    }

    public static void setShowUpdated(Context context, boolean value) {
        setShowUpdated(context, 0, value);
    }

    public static void setShowUpdated(Context context, int widgetId, boolean value) {
        prefs(context).edit().putBoolean(key(widgetId, KEY_SHOW_UPDATED), value).apply();
    }

    public static float opacity(Context context, int widgetId) {
        SharedPreferences preferences = prefs(context);
        return preferences.getFloat(
                key(widgetId, "opacity"),
                preferences.getFloat("opacity", DEFAULT_OPACITY));
    }

    public static void setOpacity(Context context, int widgetId, float opacity) {
        prefs(context).edit()
                .putFloat(key(widgetId, "opacity"), Math.max(0.4f, Math.min(1f, opacity)))
                .apply();
    }

    public static String name(Context context, int widgetId) {
        return prefs(context).getString(key(widgetId, "name"), "");
    }

    public static void setName(Context context, int widgetId, String name) {
        String normalized = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (normalized.length() > 40) {
            normalized = normalized.substring(0, 40).trim();
        }
        prefs(context).edit().putString(key(widgetId, "name"), normalized).apply();
    }

    public static void clear(Context context, int widgetId) {
        String prefix = "widget_" + widgetId + "_";
        SharedPreferences preferences = prefs(context);
        SharedPreferences.Editor editor = preferences.edit();
        for (String preference : preferences.getAll().keySet()) {
            if (preference.startsWith(prefix)) {
                editor.remove(preference);
            }
        }
        editor.apply();
    }

    private static String key(int widgetId, String preference) {
        return widgetId > 0 ? "widget_" + widgetId + "_" + preference : preference;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
