package com.mcmonitor.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.format.DateFormat;
import android.util.Base64;
import android.util.Log;
import android.widget.RemoteViews;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Строит и обновляет все три размера виджета. */
public final class WidgetUpdater {

    private static final String TAG = "MCMonitorWidgets";
    public static final int KIND_2X2 = WidgetStore.KIND_2X2;
    public static final int KIND_2X1 = WidgetStore.KIND_2X1;
    public static final int KIND_3X3 = WidgetStore.KIND_3X3;
    public static final int KIND_GROUP = WidgetStore.KIND_GROUP;

    private static final int COLOR_TEXT = 0xFFE6E9E5;
    private static final int COLOR_MINT = 0xFFA7C6A4;
    private static final int COLOR_CORAL = 0xFFD0A09B;
    private static final int COLOR_AMBER = 0xFFC9BB98;
    private static final int[] WIDGET_BACKGROUNDS = WidgetSettingsStore.BACKGROUNDS;

    private WidgetUpdater() {
    }

    public static Class<?> providerClass(int kind) {
        if (kind == KIND_2X1) {
            return Widget2x1Provider.class;
        }
        if (kind == KIND_3X3) {
            return Widget3x3Provider.class;
        }
        if (kind == KIND_GROUP) {
            return GroupWidgetProvider.class;
        }
        return Widget2x2Provider.class;
    }

    public static int kindOf(Class<?> providerClass) {
        if (providerClass == Widget2x1Provider.class) {
            return KIND_2X1;
        }
        if (providerClass == Widget3x3Provider.class) {
            return KIND_3X3;
        }
        if (providerClass == GroupWidgetProvider.class) {
            return KIND_GROUP;
        }
        return KIND_2X2;
    }

    public static int layoutFor(int kind) {
        if (kind == KIND_2X1) {
            return R.layout.widget_remote_2x1;
        }
        if (kind == KIND_3X3) {
            return R.layout.widget_remote_3x3;
        }
        if (kind == KIND_GROUP) {
            return R.layout.widget_remote_group;
        }
        return R.layout.widget_remote_2x2;
    }

    /** Перерисовывает все виджеты из кэша — без сети. */
    public static void renderAll(Context context) {
        renderAll(context, false);
    }

    public static void renderAllRefreshing(Context context) {
        renderAll(context, true);
    }

    private static void renderAll(Context context, boolean refreshing) {
        Context app = AppLanguage.wrap(context.getApplicationContext());
        try {
            ensureMapped(app);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Не удалось сопоставить виджеты с серверами", exception);
        }
        for (Map.Entry<Integer, WidgetStore.Info> entry : WidgetStore.all(app).entrySet()) {
            try {
                render(app, entry.getKey(), entry.getValue().kind, entry.getValue().address,
                        refreshing, entry.getValue().groupId);
            } catch (RuntimeException exception) {
                Log.e(TAG, "Не удалось обновить виджет " + entry.getKey(), exception);
                showLoading(app, entry.getKey(), entry.getValue().kind);
            }
        }
    }

    public static void showLoading(Context context, int widgetId) {
        showLoading(context, widgetId, KIND_2X2);
    }

    public static void showLoading(Context context, int widgetId, int kind) {
        try {
            context = AppLanguage.wrap(context.getApplicationContext());
            int layout = kind == KIND_GROUP ? R.layout.widget_remote_group
                    : kind == KIND_2X1 ? R.layout.widget_loading_2x1 : R.layout.widget_loading;
            RemoteViews views = new RemoteViews(context.getPackageName(), layout);
            if (kind == KIND_2X1) {
                views.setTextViewText(
                        R.id.loadingTitle, context.getString(R.string.widget_loading_short));
            } else if (kind != KIND_GROUP) {
                views.setTextViewText(
                        R.id.loadingText, context.getString(R.string.widget_loading));
            } else {
                views.setTextViewText(
                        R.id.groupEmpty, context.getString(R.string.widget_group_empty));
                views.setContentDescription(
                        R.id.groupRefresh, context.getString(R.string.widget_refresh_description));
            }
            AppWidgetManager.getInstance(context).updateAppWidget(
                    widgetId, views);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Не удалось показать виджет " + widgetId, exception);
        }
    }

    /** Виджет, добавленный через лаунчер, привязываем к первому серверу. */
    public static void ensureMapped(Context context) {
        Context app = context.getApplicationContext();
        List<ServerEntry> servers = ServerStore.load(app);
        if (servers.isEmpty()) {
            return;
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(app);
        for (int kind = KIND_2X2; kind <= KIND_GROUP; kind++) {
            if (WidgetStore.hasPending(app, kind)) {
                continue;
            }
            int[] ids = manager.getAppWidgetIds(
                    new ComponentName(app, providerClass(kind)));
            for (int widgetId : ids) {
                if (WidgetStore.get(app, widgetId) == null) {
                    if (kind == KIND_GROUP) {
                        ServerEntry grouped = firstGroupedServer(servers);
                        if (grouped != null) {
                            WidgetStore.putGroup(
                                    app, widgetId, grouped.groupId, grouped.address);
                        }
                    } else {
                        WidgetStore.put(app, widgetId, servers.get(0).address, kind);
                    }
                }
            }
        }
    }

    /** Есть ли вообще хоть один виджет на экране. */
    public static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int kind = KIND_2X2; kind <= KIND_GROUP; kind++) {
            if (manager.getAppWidgetIds(new ComponentName(context, providerClass(kind))).length > 0) {
                return true;
            }
        }
        return false;
    }

    public static void updateWidget(Context context, int widgetId, boolean fetch) {
        Context app = AppLanguage.wrap(context.getApplicationContext());
        WidgetStore.Info info = WidgetStore.get(app, widgetId);
        if (info == null) {
            return;
        }
        render(app, widgetId, info.kind, info.address, false, info.groupId);
        if (fetch) {
            refreshWidgetAsync(app, widgetId);
        }
    }

    /** Обновляет один виджет после нажатия на стрелку. */
    public static void refreshWidgetAsync(Context context, int widgetId) {
        updateWidget(context, widgetId, false);
        WidgetJobService.requestRefresh(context);
    }

    /** Обновляет все виджеты с сетью (boot, ручное обновление). */
    public static void refreshAllAsync(Context context) {
        WidgetJobService.requestRefresh(context);
    }

    /**
     * Синхронное обновление: тянет статусы и перерисовывает. Вызывать только
     * из фонового потока — используется службой JobScheduler, чтобы система
     * не убила процесс на середине запроса.
     */
    public static void refreshAllBlocking(Context context) {
        Context app = AppLanguage.wrap(context.getApplicationContext());
        renderAllRefreshing(app);

        Set<String> addresses = new HashSet<>();
        List<ServerEntry> servers = ServerStore.load(app);
        for (WidgetStore.Info info : WidgetStore.all(app).values()) {
            addresses.add(info.address);
            if (info.kind == KIND_GROUP && !info.groupId.isEmpty()) {
                for (ServerEntry server : servers) {
                    if (info.groupId.equals(server.groupId)) {
                        addresses.add(server.address);
                    }
                }
            }
        }
        for (ServerEntry server : servers) {
            if (server.priorityPlayers != null && !server.priorityPlayers.isEmpty()) {
                addresses.add(server.address);
            }
        }
        if (addresses.isEmpty() && hasWidgets(app)) {
            for (ServerEntry entry : servers) {
                addresses.add(entry.address);
            }
        }

        for (String address : addresses) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            ServerEntry server = ServerStore.findByAddress(app, address);
            ServerStatus previous = StatusCache.load(app, address);
            ServerStatus status = server == null
                    ? MinecraftApi.fetch(address) : MinecraftApi.fetch(server);
            PriorityPlayerNotifications.notifyChanges(app, server, previous, status);
            StatusCache.save(app, status);
        }
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        renderAll(app);
    }

    private static void render(
            Context context,
            int widgetId,
            int kind,
            String address,
            boolean refreshing,
            String groupId) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        if (manager.getAppWidgetInfo(widgetId) == null) {
            // Виджет мог ещё не успеть зарегистрироваться — привязку не теряем,
            // её подхватит ближайший onUpdate.
            return;
        }
        RemoteViews views = kind == KIND_GROUP
                ? buildGroup(context, widgetId, groupId)
                : build(context, widgetId, kind, address, refreshing);
        manager.updateAppWidget(widgetId, views);
        if (kind == KIND_GROUP) {
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.groupStack);
        }
    }

    static RemoteViews build(
            Context context, int widgetId, int kind, String address, boolean refreshing) {
        context = AppLanguage.wrap(context);
        RemoteViews views = new RemoteViews(context.getPackageName(), layoutFor(kind));

        ServerEntry server = ServerStore.findByAddress(context, address);
        ServerStatus status = StatusCache.load(context, address);
        String name = server != null ? server.displayName() : ServerEntry.hostOf(address);

        boolean known = status != null && status.hasData;
        boolean online = known && status.online;
        int accent = WidgetSettingsStore.accent(context, widgetId);
        int backgroundIndex = WidgetSettingsStore.background(context, widgetId);
        if (backgroundIndex < 0 || backgroundIndex >= WIDGET_BACKGROUNDS.length) {
            backgroundIndex = 0;
        }
        int background = WIDGET_BACKGROUNDS[backgroundIndex];
        int alpha = Math.round(
                android.graphics.Color.alpha(background)
                        * WidgetSettingsStore.opacity(context, widgetId));
        views.setInt(R.id.wRoot, "setBackgroundColor",
                android.graphics.Color.argb(
                        alpha,
                        android.graphics.Color.red(background),
                        android.graphics.Color.green(background),
                        android.graphics.Color.blue(background)));

        Bitmap icon = decodeServerIcon(status);
        if (icon != null) {
            views.setImageViewBitmap(R.id.wServerIcon, icon);
        } else {
            views.setImageViewResource(R.id.wServerIcon, R.drawable.ic_server_fallback);
        }
        String customName = WidgetSettingsStore.name(context, widgetId);
        views.setTextViewText(R.id.wName, customName.isEmpty() ? name : customName);
        views.setContentDescription(
                R.id.wServerIcon, context.getString(R.string.widget_server_icon_description));
        views.setContentDescription(
                R.id.wRefresh, context.getString(R.string.widget_refresh_description));
        views.setTextViewText(R.id.wCount,
                !known ? "—" : online ? String.valueOf(status.onlineCount) : "—");
        views.setTextColor(R.id.wCount, known && online ? accent : COLOR_TEXT);
        views.setTextViewText(R.id.wMax,
                known && online && status.maxCount > 0 ? "/ " + status.maxCount : "");
        views.setViewVisibility(R.id.wName,
                WidgetSettingsStore.showName(context, widgetId)
                        ? android.view.View.VISIBLE : android.view.View.GONE);
        views.setTextColor(R.id.wRefresh, accent);

        if (kind != KIND_2X1) {
            views.setTextColor(R.id.wDot, statusColor(status, refreshing));
            views.setTextViewText(R.id.wStatus, statusText(context, status, refreshing));
            views.setTextColor(R.id.wStatus, statusColor(status, refreshing));
            views.setViewVisibility(R.id.wStatus, android.view.View.VISIBLE);
        }

        if (kind == KIND_3X3) {
            views.setTextViewText(
                    R.id.wNowPlayingLabel, context.getString(R.string.widget_playing_now));
            views.setTextViewText(R.id.wAddress, address);
            views.setTextViewText(R.id.wLastUpdated, updatedText(context, status));
            views.setTextViewText(R.id.wVersion, versionText(context, status, server));
            views.setTextColor(R.id.wPlayersLabel, accent);
            views.setTextViewText(R.id.wPlayersLabel, known && online
                    ? context.getString(R.string.widget_players_count_label, status.onlineCount)
                    : context.getString(R.string.widget_players_label));
            views.setTextViewText(R.id.wPlayersEmpty, playersText(context, status, 9));
            views.setViewVisibility(R.id.wPlayersLabel,
                    WidgetSettingsStore.showPlayers(context, widgetId)
                            ? android.view.View.VISIBLE : android.view.View.GONE);
            views.setViewVisibility(R.id.wPlayersEmpty,
                    WidgetSettingsStore.showPlayers(context, widgetId)
                            ? android.view.View.VISIBLE : android.view.View.GONE);
            views.setViewVisibility(R.id.wVersion,
                    WidgetSettingsStore.showVersion(context, widgetId)
                            ? android.view.View.VISIBLE : android.view.View.GONE);
            views.setViewVisibility(R.id.wLastUpdated,
                    WidgetSettingsStore.showUpdated(context, widgetId)
                            ? android.view.View.VISIBLE : android.view.View.GONE);
        } else if (kind == KIND_2X2) {
            views.setTextColor(R.id.wPlayersLabel, accent);
            views.setTextViewText(
                    R.id.wPlayersLabel, context.getString(R.string.widget_players_label));
            views.setTextViewText(R.id.wPlayersEmpty, playersText(context, status, 2));
            views.setViewVisibility(R.id.wPlayersLabel,
                    WidgetSettingsStore.showPlayers(context, widgetId)
                            ? android.view.View.VISIBLE : android.view.View.GONE);
            views.setViewVisibility(R.id.wPlayersEmpty,
                    WidgetSettingsStore.showPlayers(context, widgetId)
                            ? android.view.View.VISIBLE : android.view.View.GONE);
        }

        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context, 3000 + widgetId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.wRoot, openPending);

        Intent refreshIntent = new Intent(context, WidgetRefreshReceiver.class)
                .setAction(WidgetRefreshReceiver.ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        refreshIntent.setData(Uri.parse("mcmonitor://widget-refresh/" + widgetId));
        PendingIntent refreshPending = PendingIntent.getBroadcast(
                context, 2000 + widgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.wRefresh, refreshPending);

        return views;
    }

    private static RemoteViews buildGroup(Context context, int widgetId, String groupId) {
        context = AppLanguage.wrap(context);
        RemoteViews views = new RemoteViews(
                context.getPackageName(), R.layout.widget_remote_group);
        int backgroundIndex = WidgetSettingsStore.background(context, widgetId);
        if (backgroundIndex < 0 || backgroundIndex >= WIDGET_BACKGROUNDS.length) {
            backgroundIndex = 0;
        }
        int background = WIDGET_BACKGROUNDS[backgroundIndex];
        int alpha = Math.round(
                android.graphics.Color.alpha(background)
                        * WidgetSettingsStore.opacity(context, widgetId));
        views.setInt(
                R.id.groupRoot,
                "setBackgroundColor",
                android.graphics.Color.argb(
                        alpha,
                        android.graphics.Color.red(background),
                        android.graphics.Color.green(background),
                        android.graphics.Color.blue(background)));
        String customName = WidgetSettingsStore.name(context, widgetId);
        views.setTextViewText(
                R.id.groupTitle,
                customName.isEmpty() ? (groupId == null ? "" : groupId) : customName);
        views.setTextColor(R.id.groupRefresh, WidgetSettingsStore.accent(context, widgetId));
        views.setTextViewText(
                R.id.groupEmpty, context.getString(R.string.widget_group_empty));
        views.setContentDescription(
                R.id.groupRefresh, context.getString(R.string.widget_refresh_description));
        views.setContentDescription(
                R.id.groupRefresh, context.getString(R.string.widget_refresh_description));
        views.setViewVisibility(
                R.id.groupTitle,
                WidgetSettingsStore.showName(context, widgetId)
                        ? android.view.View.VISIBLE : android.view.View.GONE);

        Intent adapter = new Intent(context, GroupWidgetRemoteViewsService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .putExtra("group_id", groupId);
        adapter.setData(Uri.parse(
                "mcmonitor://group-widget/" + widgetId + "/"
                        + Uri.encode(groupId == null ? "" : groupId)));
        views.setRemoteAdapter(R.id.groupStack, adapter);
        views.setEmptyView(R.id.groupStack, R.id.groupEmpty);

        Intent openIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                context, 3000 + widgetId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.groupRoot, openPending);

        Intent refreshIntent = new Intent(context, WidgetRefreshReceiver.class)
                .setAction(WidgetRefreshReceiver.ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        refreshIntent.setData(Uri.parse("mcmonitor://widget-refresh/" + widgetId));
        PendingIntent refreshPending = PendingIntent.getBroadcast(
                context, 2000 + widgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.groupRefresh, refreshPending);
        return views;
    }

    private static ServerEntry firstGroupedServer(List<ServerEntry> servers) {
        for (ServerEntry server : servers) {
            if (server.groupId != null && !server.groupId.trim().isEmpty()) {
                return server;
            }
        }
        return null;
    }

    private static String statusText(Context context, ServerStatus status, boolean refreshing) {
        if (refreshing) {
            return context.getString(R.string.widget_status_refreshing);
        }
        if (status == null) {
            return context.getString(R.string.main_no_data);
        }
        if (!status.hasData) {
            return context.getString(R.string.main_no_connection);
        }
        return context.getString(status.online ? R.string.main_online : R.string.main_offline);
    }

    private static int statusColor(ServerStatus status, boolean refreshing) {
        if (refreshing || status == null) {
            return COLOR_AMBER;
        }
        if (!status.hasData || !status.online) {
            return COLOR_CORAL;
        }
        return COLOR_MINT;
    }

    private static String updatedText(Context context, ServerStatus status) {
        if (status == null || status.updatedAt <= 0L) {
            return context.getString(R.string.widget_not_checked);
        }
        return context.getString(
                R.string.widget_updated_at,
                DateFormat.getTimeFormat(context).format(new Date(status.updatedAt)));
    }

    private static Bitmap decodeServerIcon(ServerStatus status) {
        if (status == null || status.favicon == null || status.favicon.isEmpty()) {
            return null;
        }
        try {
            byte[] data = Base64.decode(status.favicon, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (IllegalArgumentException exception) {
            Log.w(TAG, "Некорректная иконка сервера " + status.address, exception);
            return null;
        }
    }

    private static String versionText(Context context, ServerStatus status, ServerEntry server) {
        if (status == null) {
            return context.getString(R.string.widget_not_checked);
        }
        if (!status.hasData) {
            return status.error == null || status.error.isEmpty()
                    ? context.getString(R.string.main_no_connection) : status.error;
        }
        if (!status.online) {
            return context.getString(R.string.widget_server_offline);
        }
        String version = status.version == null ? "" : status.version.trim();
        String edition = server != null && server.isBedrock()
                ? context.getString(R.string.main_bedrock_prefix) : "";
        if (server != null && !version.isEmpty()) {
            String name = server.displayName();
            if (!name.isEmpty() && version.regionMatches(true, 0, name, 0, name.length())) {
                version = version.substring(name.length()).trim();
                while (version.startsWith("-") || version.startsWith("|") || version.startsWith("—")) {
                    version = version.substring(1).trim();
                }
            }
        }
        return version.isEmpty()
                ? edition + context.getString(R.string.main_online) : edition + version;
    }

    private static String emptyText(Context context, ServerStatus status) {
        if (status == null) {
            return context.getString(R.string.widget_players_loading);
        }
        if (!status.hasData) {
            return context.getString(R.string.main_server_unreachable);
        }
        if (!status.online) {
            return context.getString(R.string.main_server_offline);
        }
        if (!status.players.isEmpty()) {
            return "";
        }
        return context.getString(status.onlineCount > 0
                ? R.string.main_server_hides_players : R.string.main_no_players_online);
    }

    private static String playersText(Context context, ServerStatus status, int maxPlayers) {
        String empty = emptyText(context, status);
        if (status == null || !status.hasData || !status.online || status.players.isEmpty()) {
            return empty;
        }
        StringBuilder builder = new StringBuilder();
        int count = Math.min(status.players.size(), maxPlayers);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append("• ").append(status.players.get(i));
        }
        if (status.players.size() > count) {
            builder.append('\n').append(context.getString(
                    R.string.widget_more_players, status.players.size() - count));
        }
        return builder.toString();
    }

}
