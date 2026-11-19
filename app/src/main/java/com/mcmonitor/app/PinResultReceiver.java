package com.mcmonitor.app;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.util.List;

/** Система сообщает id только что закреплённого виджета. */
public class PinResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int kind = intent.getIntExtra("kind", WidgetStore.KIND_2X2);
        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);

        if (widgetId == -1) {
            WidgetStore.clearPending(context);
            return;
        }

        String address = intent.getStringExtra("address");
        if (address == null || address.isEmpty()) {
            address = WidgetStore.claimPending(context, kind);
        } else {
            WidgetStore.clearPending(context);
        }
        if (address == null) {
            WidgetStore.Info info = WidgetStore.get(context, widgetId);
            if (info != null) {
                address = info.address;
            }
        }
        if (address == null) {
            List<ServerEntry> servers = ServerStore.load(context);
            if (!servers.isEmpty()) {
                address = servers.get(0).address;
            }
        }
        if (address != null) {
            WidgetStore.put(context, widgetId, address, kind);
            WidgetUpdater.updateWidget(context, widgetId, false);
            WidgetJobService.schedule(context);
            WidgetJobService.requestRefresh(context);
            Toast.makeText(context, "Виджет добавлен", Toast.LENGTH_SHORT).show();
        }
    }
}
