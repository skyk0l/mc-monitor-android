package com.mcmonitor.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Список серверов в SharedPreferences. */
public final class ServerStore {

    private static final String PREFS = "mcmonitor";
    private static final String KEY_SERVERS = "servers_v1";

    private ServerStore() {
    }

    public static synchronized List<ServerEntry> load(Context context) {
        List<ServerEntry> entries = new ArrayList<>();
        String raw = prefs(context).getString(KEY_SERVERS, null);
        if (raw == null || raw.isEmpty()) {
            return entries;
        }
        try {
            JSONArray array = new JSONArray(raw);
            boolean needsMigration = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject serverJson = array.optJSONObject(i);
                if (serverJson != null && !serverJson.has("added_at")) {
                    needsMigration = true;
                }
                ServerEntry entry = ServerEntry.fromJson(serverJson);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            if (needsMigration) {
                save(context, entries);
            }
        } catch (Exception ignored) {
        }
        return entries;
    }

    public static synchronized void save(Context context, List<ServerEntry> entries) {
        JSONArray array = new JSONArray();
        for (ServerEntry entry : entries) {
            try {
                array.put(entry.toJson());
            } catch (Exception ignored) {
            }
        }
        prefs(context).edit().putString(KEY_SERVERS, array.toString()).apply();
    }

    public static synchronized void saveEntry(Context context, ServerEntry entry) {
        if (entry == null) {
            return;
        }
        List<ServerEntry> entries = load(context);
        boolean replaced = false;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id.equals(entry.id)) {
                entries.set(i, entry);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            entries.add(entry);
        }
        save(context, entries);
    }

    public static synchronized ServerEntry findById(Context context, String id) {
        if (id == null) {
            return null;
        }
        for (ServerEntry entry : load(context)) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public static synchronized ServerEntry findByAddress(Context context, String address) {
        String normalized = ServerEntry.normalize(address);
        for (ServerEntry entry : load(context)) {
            if (entry.address.equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    public static synchronized boolean isAddressTaken(Context context, String address, String exceptId) {
        String normalized = ServerEntry.normalize(address);
        for (ServerEntry entry : load(context)) {
            if (entry.address.equals(normalized) && (exceptId == null || !entry.id.equals(exceptId))) {
                return true;
            }
        }
        return false;
    }

    public static synchronized void delete(Context context, String id) {
        List<ServerEntry> entries = load(context);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).id.equals(id)) {
                entries.remove(i);
            }
        }
        save(context, entries);
    }

    public static synchronized void setAutoName(Context context, String id, String autoName) {
        ServerEntry entry = findById(context, id);
        if (entry == null || autoName == null || autoName.equals(entry.autoName)) {
            return;
        }
        entry.autoName = autoName;
        saveEntry(context, entry);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
