package com.mcmonitor.app;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

public abstract class LocalizedActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        String language = base.getSharedPreferences("app_settings", MODE_PRIVATE)
                .getString(AppLanguage.PREFERENCE_KEY, AppLanguage.SYSTEM);
        if (AppLanguage.SYSTEM.equals(language)) {
            super.attachBaseContext(base);
            return;
        }

        Locale locale = Locale.forLanguageTag(language);
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locale));
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }
}
