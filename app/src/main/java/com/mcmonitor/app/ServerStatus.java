package com.mcmonitor.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Результат опроса сервера. */
public class ServerStatus {

    public String address = "";
    /** true, если API вообще ответил (даже если сервер офлайн). */
    public boolean hasData = false;
    public boolean online = false;
    public int onlineCount = 0;
    public int maxCount = 0;
    public List<String> players = new ArrayList<>();
    public String version = "";
    public String motd = "";
    /** PNG в base64 без data-префикса. */
    public String favicon = "";
    public String error = "";
    public long updatedAt = 0L;
    /** Round-trip time to the public status provider, not the game server itself. */
    public long pingMs = -1L;

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("address", address);
        json.put("hasData", hasData);
        json.put("online", online);
        json.put("onlineCount", onlineCount);
        json.put("maxCount", maxCount);
        JSONArray list = new JSONArray();
        for (String player : players) {
            list.put(player);
        }
        json.put("players", list);
        json.put("version", version);
        json.put("motd", motd);
        json.put("favicon", favicon);
        json.put("error", error);
        json.put("updatedAt", updatedAt);
        json.put("pingMs", pingMs);
        return json;
    }

    public static ServerStatus fromJson(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(raw);
            ServerStatus status = new ServerStatus();
            status.address = json.optString("address", "");
            status.hasData = json.optBoolean("hasData", false);
            status.online = json.optBoolean("online", false);
            status.onlineCount = json.optInt("onlineCount", 0);
            status.maxCount = json.optInt("maxCount", 0);
            JSONArray list = json.optJSONArray("players");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    String player = list.optString(i, "").trim();
                    if (!player.isEmpty()) {
                        status.players.add(player);
                    }
                }
            }
            status.version = json.optString("version", "");
            status.motd = json.optString("motd", "");
            status.favicon = json.optString("favicon", "");
            status.error = json.optString("error", "");
            status.updatedAt = json.optLong("updatedAt", 0L);
            status.pingMs = json.optLong("pingMs", -1L);
            return status;
        } catch (JSONException ignored) {
            return null;
        }
    }
}
