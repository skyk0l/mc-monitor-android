package com.mcmonitor.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** После перезагрузки телефона сразу показываем кэш и тихо обновляем данные. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        WidgetUpdater.renderAll(context);
        WidgetJobService.schedule(context);
        WidgetJobService.requestRefresh(context);
    }
}
