package com.mcmonitor.app;

import android.app.Activity;
import android.content.Intent;

public final class AppLanguage {
    public static final String PREFERENCE_KEY = "app_language";
    public static final String SYSTEM = "system";

    private AppLanguage() {
    }

    public static String selected(Activity activity) {
        return activity.getSharedPreferences("app_settings", Activity.MODE_PRIVATE)
                .getString(PREFERENCE_KEY, SYSTEM);
    }

    public static void setAndRestart(Activity activity, String language) {
        activity.getSharedPreferences("app_settings", Activity.MODE_PRIVATE)
                .edit()
                .putString(PREFERENCE_KEY, language)
                .apply();
        Intent intent = new Intent(activity, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finishAffinity();
    }
}
