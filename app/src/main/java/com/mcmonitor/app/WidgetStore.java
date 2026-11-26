package com.mcmonitor.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Привязка «id виджета → сервер + размер». */
public final class WidgetStore {

    public static final int KIND_2X2 = 0;
    public static final int KIND_2X1 = 1;
    public static final int KIND_3X3 = 2;
    public static final int KIND_GROUP = 3;

    private static final String PREFS = "mcmonitor";
    private static final String KEY_MAP = "widget_map_v1";
    private static final String KEY_PENDING_ADDRESS = "widget_pending_address";
    private static final String KEY_PENDING_KIND = "widget_pending_kind";
    private static final String KEY_PENDING_TIME = "widget_pending_time";
    private static final long PENDING_TTL = 5 * 60 * 1000L;

    private WidgetStore() {
    }

    public static class Info {
        public final String address;
        public final int kind;
        public final String groupId;

        public Info(String address, int kind, String groupId) {
            this.address = address;
            this.kind = kind;
            this.groupId = groupId;
        }
    }

    public static synchronized void put(Context context, int widgetId, String address, int kind) {
        save(context, widgetId, address, kind, "");
    }

    public static synchronized void putGroup(
            Context context, int widgetId, String groupId, String fallbackAddress) {
        if (groupId == null || groupId.trim().isEmpty()) {
            return;
        }
        save(context, widgetId, fallbackAddress, KIND_GROUP, groupId.trim());
    }

    private static void save(
            Context context, int widgetId, String address, int kind, String groupId) {
        if (address == null || address.isEmpty()) {
            return;
        }
        JSONObject map = readMap(context);
        try {
            JSONObject item = new JSONObject();
            item.put("address", address);
            item.put("kind", kind);
            item.put("group_id", groupId);
            map.put(String.valueOf(widgetId), item);
        } catch (Exception ignored) {
        }
        writeMap(context, map);
    }

    public static synchronized Info get(Context context, int widgetId) {
        JSONObject map = readMap(context);
        JSONObject item = map.optJSONObject(String.valueOf(widgetId));
        if (item == null) {
            return null;
        }
        String address = item.optString("address", "");
        if (address.isEmpty()) {
            return null;
        }
        return new Info(
                address,
                item.optInt("kind", KIND_2X2),
                item.optString("group_id", ""));
    }

    public static synchronized void remove(Context context, int widgetId) {
        JSONObject map = readMap(context);
        map.remove(String.valueOf(widgetId));
        writeMap(context, map);
    }

    public static synchronized Map<Integer, Info> all(Context context) {
        Map<Integer, Info> result = new LinkedHashMap<>();
        JSONObject map = readMap(context);
        java.util.Iterator<String> keys = map.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                int id = Integer.parseInt(key);
                JSONObject item = map.optJSONObject(key);
                if (item == null) {
                    continue;
                }
                String address = item.optString("address", "");
                if (!address.isEmpty()) {
                    result.put(id, new Info(
                            address,
                            item.optInt("kind", KIND_2X2),
                            item.optString("group_id", "")));
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public static synchronized void setPending(Context context, String address, int kind) {
        prefs(context).edit()
                .putString(KEY_PENDING_ADDRESS, address)
                .putInt(KEY_PENDING_KIND, kind)
                .putLong(KEY_PENDING_TIME, System.currentTimeMillis())
                .apply();
    }

    /** Забирает адрес, если он ждал именно этот размер виджета. */
    public static synchronized String claimPending(Context context, int kind) {
        SharedPreferences prefs = prefs(context);
        String address = prefs.getString(KEY_PENDING_ADDRESS, null);
        int pendingKind = prefs.getInt(KEY_PENDING_KIND, -1);
        long time = prefs.getLong(KEY_PENDING_TIME, 0L);
        if (address == null || address.isEmpty()) {
            return null;
        }
        if (pendingKind != kind || System.currentTimeMillis() - time > PENDING_TTL) {
            return null;
        }
        clearPending(context);
        return address;
    }

    /** Есть ли ожидающий привязки виджет этого размера (чтобы не перехватить его первым сервером). */
    public static synchronized boolean hasPending(Context context, int kind) {
        SharedPreferences prefs = prefs(context);
        String address = prefs.getString(KEY_PENDING_ADDRESS, null);
        int pendingKind = prefs.getInt(KEY_PENDING_KIND, -1);
        long time = prefs.getLong(KEY_PENDING_TIME, 0L);
        return address != null && !address.isEmpty()
                && pendingKind == kind
                && System.currentTimeMillis() - time <= PENDING_TTL;
    }

    public static synchronized void clearPending(Context context) {
        prefs(context).edit()
                .remove(KEY_PENDING_ADDRESS)
                .remove(KEY_PENDING_KIND)
                .remove(KEY_PENDING_TIME)
                .apply();
    }

    private static JSONObject readMap(Context context) {
        String raw = prefs(context).getString(KEY_MAP, null);
        if (raw == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void writeMap(Context context, JSONObject map) {
        prefs(context).edit().putString(KEY_MAP, map.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
