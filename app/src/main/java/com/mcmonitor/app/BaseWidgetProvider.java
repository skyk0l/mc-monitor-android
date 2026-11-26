package com.mcmonitor.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.util.Log;

import java.util.List;

/** Общая логика трёх виджетов. */
public abstract class BaseWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "MCMonitorWidgets";

    protected abstract int kind();

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            WidgetUpdater.showLoading(context, widgetId, kind());
        }
        for (int widgetId : appWidgetIds) {
            try {
                if (WidgetStore.get(context, widgetId) != null) {
                    continue;
                }
                if (kind() == WidgetStore.KIND_GROUP) {
                    if (WidgetStore.hasPending(context, kind())) {
                        continue;
                    }
                    for (ServerEntry server : ServerStore.load(context)) {
                        if (server.groupId != null && !server.groupId.trim().isEmpty()) {
                            WidgetStore.putGroup(
                                    context, widgetId, server.groupId, server.address);
                            break;
                        }
                    }
                    continue;
                }
                String pending = WidgetStore.claimPending(context, kind());
                if (pending != null) {
                    WidgetStore.put(context, widgetId, pending, kind());
                    continue;
                }
                List<ServerEntry> servers = ServerStore.load(context);
                if (!servers.isEmpty()) {
                    WidgetStore.put(context, widgetId, servers.get(0).address, kind());
                }
            } catch (RuntimeException exception) {
                Log.e(TAG, "Не удалось привязать виджет " + widgetId + " к серверу", exception);
            }
        }
        WidgetUpdater.renderAll(context);
        WidgetJobService.schedule(context);
        WidgetJobService.requestRefresh(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        for (int widgetId : appWidgetIds) {
            WidgetStore.remove(context, widgetId);
        }
        WidgetJobService.schedule(context);
    }
}
