package com.mcmonitor.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Один Java-сервер в списке приложения. */
public class ServerEntry {

    public static final String PROTOCOL_JAVA = "java";
    public static final String PROTOCOL_BEDROCK = "bedrock";

    public String id;
    /** Адрес вида host или host:port, всегда нормализованный. */
    public String address;
    /** Имя, которое задал пользователь. Может быть пустым. */
    public String name;
    /** Имя, которое мы вытащили из MOTD. */
    public String autoName;
    public String protocol;
    public String groupId;
    public boolean showInWidget;
    public List<String> priorityPlayers;

    public ServerEntry() {
        id = UUID.randomUUID().toString();
        address = "";
        name = "";
        autoName = "";
        protocol = PROTOCOL_JAVA;
        groupId = "";
        priorityPlayers = new ArrayList<>();
    }

    public ServerEntry(String address, String name) {
        this();
        this.address = normalize(address);
        this.name = name == null ? "" : name.trim();
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) {
            return name.trim();
        }
        if (autoName != null && !autoName.trim().isEmpty()) {
            return autoName.trim();
        }
        return hostOf(address);
    }

    public String host() {
        return hostOf(address);
    }

    public boolean isBedrock() {
        return PROTOCOL_BEDROCK.equals(normalizeProtocol(protocol));
    }

    public static String normalizeProtocol(String value) {
        return PROTOCOL_BEDROCK.equalsIgnoreCase(value == null ? "" : value.trim())
                ? PROTOCOL_BEDROCK
                : PROTOCOL_JAVA;
    }

    public static String hostOf(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close > 0) {
                return value.substring(0, close + 1);
            }
        }
        int first = value.indexOf(':');
        if (first > 0 && first == value.lastIndexOf(':')) {
            return value.substring(0, first);
        }
        return value;
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("https://")) {
            value = value.substring(8);
        } else if (value.startsWith("http://")) {
            value = value.substring(7);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        value = value.replaceAll("\\s+", "");
        if (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("address", address);
        json.put("name", name == null ? "" : name);
        json.put("autoName", autoName == null ? "" : autoName);
        json.put("protocol", normalizeProtocol(protocol));
        json.put("group_id", groupId == null ? "" : groupId);
        json.put("show_in_widget", showInWidget);
        JSONArray priorities = new JSONArray();
        if (priorityPlayers != null) {
            for (String player : priorityPlayers) {
                if (player != null && !player.trim().isEmpty()) {
                    priorities.put(player.trim());
                }
            }
        }
        json.put("priority_players", priorities);
        return json;
    }

    public static ServerEntry fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        ServerEntry entry = new ServerEntry();
        entry.id = json.optString("id", entry.id);
        entry.address = normalize(json.optString("address", ""));
        entry.name = json.optString("name", "");
        entry.autoName = json.optString("autoName", "");
        entry.protocol = normalizeProtocol(json.optString("protocol", PROTOCOL_JAVA));
        entry.groupId = json.optString("group_id", "");
        entry.showInWidget = json.optBoolean("show_in_widget", false);
        JSONArray priorities = json.optJSONArray("priority_players");
        if (priorities != null) {
            for (int i = 0; i < priorities.length(); i++) {
                String player = priorities.optString(i, "").trim();
                boolean duplicate = false;
                for (String existing : entry.priorityPlayers) {
                    if (existing.equalsIgnoreCase(player)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!player.isEmpty() && player.length() <= 32 && !duplicate) {
                    entry.priorityPlayers.add(player);
                }
            }
        }
        if (entry.address.isEmpty()) {
            return null;
        }
        return entry;
    }
}
