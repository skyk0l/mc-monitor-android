package com.mcmonitor.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

public final class AppLanguage {
    public static final String PREFERENCE_KEY = "app_language";
    public static final String SYSTEM = "system";

    private AppLanguage() {
    }

    public static String selected(Context context) {
        return context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getString(PREFERENCE_KEY, SYSTEM);
    }

    public static Context wrap(Context base) {
        String language = selected(base);
        if (SYSTEM.equals(language)) {
            return base;
        }
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.forLanguageTag(language)));
        return base.createConfigurationContext(configuration);
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
