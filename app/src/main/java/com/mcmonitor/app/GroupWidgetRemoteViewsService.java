package com.mcmonitor.app;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

public final class GroupWidgetRemoteViewsService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new GroupFactory(
                getApplicationContext(),
                intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
                intent.getStringExtra("group_id"));
    }

    private static final class GroupFactory implements RemoteViewsFactory {
        private final Context context;
        private final int widgetId;
        private final String groupId;
        private final List<ServerEntry> servers = new ArrayList<>();

        GroupFactory(Context context, int widgetId, String groupId) {
            this.context = context;
            this.widgetId = widgetId;
            this.groupId = groupId == null ? "" : groupId;
        }

        @Override
        public void onCreate() {
            loadServers();
        }

        @Override
        public void onDataSetChanged() {
            loadServers();
        }

        @Override
        public void onDestroy() {
            servers.clear();
        }

        @Override
        public int getCount() {
            return servers.size();
        }

        @Override
        public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= servers.size()) {
                return null;
            }
            return WidgetUpdater.build(
                    context,
                    widgetId,
                    WidgetStore.KIND_2X2,
                    servers.get(position).address,
                    false);
        }

        @Override
        public RemoteViews getLoadingView() {
            return new RemoteViews(context.getPackageName(), R.layout.widget_loading);
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position >= 0 && position < servers.size()
                    ? servers.get(position).id.hashCode() : position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        private void loadServers() {
            servers.clear();
            if (groupId.isEmpty()) {
                return;
            }
            for (ServerEntry server : ServerStore.load(context)) {
                if (groupId.equalsIgnoreCase(server.groupId)) {
                    servers.add(server);
                }
            }
        }
    }
}
