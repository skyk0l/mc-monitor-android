package com.mcmonitor.app;

import android.app.Activity;
import android.content.Context;
public abstract class LocalizedActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLanguage.wrap(base));
    }
}
