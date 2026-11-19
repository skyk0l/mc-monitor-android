package com.mcmonitor.app;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Нажатие на стрелку внутри виджета. */
public class WidgetRefreshReceiver extends BroadcastReceiver {

    public static final String ACTION_REFRESH = "com.mcmonitor.app.action.WIDGET_REFRESH";

    @Override
    public void onReceive(Context context, Intent intent) {
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        if (widgetId != -1) {
            WidgetUpdater.updateWidget(context, widgetId, false);
            WidgetJobService.requestRefresh(context);
        } else {
            WidgetJobService.requestRefresh(context);
        }
    }
}
