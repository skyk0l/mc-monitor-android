package com.mcmonitor.app;

import android.content.Context;

import org.json.JSONObject;

/** Кэш последнего статуса, чтобы приложение и виджеты показывали данные мгновенно. */
public final class StatusCache {

    private static final String PREFS = "mcmonitor";
    private static final String PREFIX = "status_v1_";

    private StatusCache() {
    }

    public static void save(Context context, ServerStatus status) {
        if (status == null || status.address == null || status.address.isEmpty()) {
            return;
        }
        try {
            String json = status.toJson().toString();
            prefs(context).edit().putString(PREFIX + status.address, json).apply();
        } catch (Exception ignored) {
        }
    }

    public static ServerStatus load(Context context, String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        String raw = prefs(context).getString(PREFIX + address, null);
        return ServerStatus.fromJson(raw);
    }

    public static void clear(Context context, String address) {
        if (address == null || address.isEmpty()) {
            return;
        }
        prefs(context).edit().remove(PREFIX + address).apply();
    }

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
