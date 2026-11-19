package com.mcmonitor.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Public Java and Bedrock status APIs, with mcsrvstat.us as a fallback provider. */
public final class MinecraftApi {

    private static final String MCSTATUS = "https://api.mcstatus.io/v2/status/java/";
    private static final String MCSTATUS_BEDROCK = "https://api.mcstatus.io/v2/status/bedrock/";
    private static final String MCSRVSTAT = "https://api.mcsrvstat.us/3/";
    private static final String MCSRVSTAT_BEDROCK = "https://api.mcsrvstat.us/bedrock/3/";
    private static final int CONNECT_TIMEOUT = 6000;
    private static final int READ_TIMEOUT = 8000;

    private MinecraftApi() {
    }

    public static ServerStatus fetch(String rawAddress) {
        return fetch(rawAddress, ServerEntry.PROTOCOL_JAVA);
    }

    public static ServerStatus fetch(ServerEntry server) {
        if (server == null) {
            return failure("", "Сервер не найден");
        }
        return fetch(server.address, server.protocol);
    }

    public static ServerStatus fetch(String rawAddress, String protocol) {
        String address = ServerEntry.normalize(rawAddress);
        String normalizedProtocol = ServerEntry.normalizeProtocol(protocol);
        ServerStatus status;
        if (address.isEmpty()) {
            return failure(address, "Пустой адрес");
        }

        try {
            status = fetchMcstatus(address, normalizedProtocol);
            if (status != null) {
                return status;
            }
        } catch (Exception ignored) {
        }

        try {
            status = fetchMcsrvstat(address, normalizedProtocol);
            if (status != null) {
                return status;
            }
        } catch (Exception ignored) {
        }

        return failure(address, "Нет связи с сервером");
    }

    private static ServerStatus fetchMcstatus(String address, String protocol) throws Exception {
        String host = ServerEntry.hostOf(address);
        String port = portOf(address);
        String baseUrl = ServerEntry.PROTOCOL_BEDROCK.equals(protocol)
                ? MCSTATUS_BEDROCK : MCSTATUS;
        String url = baseUrl + encode(host);
        if (!port.isEmpty()) {
            url += "?port=" + encode(port);
        }
        JSONObject json = getJson(url);
        if (json == null || !json.has("online")) {
            return null;
        }
        return parseMcstatus(json, address);
    }

    private static ServerStatus fetchMcsrvstat(String address, String protocol) throws Exception {
        String host = ServerEntry.hostOf(address);
        String port = portOf(address);
        String path = encode(host);
        if (!port.isEmpty()) {
            path += ":" + encode(port);
        }
        String baseUrl = ServerEntry.PROTOCOL_BEDROCK.equals(protocol)
                ? MCSRVSTAT_BEDROCK : MCSRVSTAT;
        JSONObject json = getJson(baseUrl + path);
        if (json == null || !json.has("online")) {
            return null;
        }
        return parseMcsrvstat(json, address);
    }

    private static ServerStatus parseMcstatus(JSONObject json, String address) {
        ServerStatus status = base(address);
        status.online = json.optBoolean("online", false);

        JSONObject players = json.optJSONObject("players");
        if (players != null) {
            status.onlineCount = Math.max(0, players.optInt("online", 0));
            status.maxCount = Math.max(0, players.optInt("max", 0));
            JSONArray list = players.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject player = list.optJSONObject(i);
                    String name = "";
                    if (player != null) {
                        name = player.optString("name_clean", "");
                        if (name.isEmpty()) {
                            name = player.optString("name_raw", "");
                        }
                    } else {
                        name = list.optString(i, "");
                    }
                    name = name.trim();
                    if (!name.isEmpty()) {
                        status.players.add(name);
                    }
                }
            }
        }

        JSONObject version = json.optJSONObject("version");
        if (version != null) {
            status.version = version.optString("name_clean", "");
            if (status.version.isEmpty()) {
                status.version = version.optString("name_raw", "");
            }
        }

        JSONObject motd = json.optJSONObject("motd");
        if (motd != null) {
            status.motd = motd.optString("clean", "");
        }

        status.favicon = cleanIcon(json.optString("icon", ""));
        if (!status.online) {
            status.players.clear();
            status.onlineCount = 0;
        }
        return status;
    }

    private static ServerStatus parseMcsrvstat(JSONObject json, String address) {
        ServerStatus status = base(address);
        status.online = json.optBoolean("online", false);

        JSONObject players = json.optJSONObject("players");
        if (players != null) {
            status.onlineCount = Math.max(0, players.optInt("online", 0));
            status.maxCount = Math.max(0, players.optInt("max", 0));
            JSONArray list = players.optJSONArray("list");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject player = list.optJSONObject(i);
                    String name = player != null ? player.optString("name", "") : list.optString(i, "");
                    name = name.trim();
                    if (!name.isEmpty()) {
                        status.players.add(name);
                    }
                }
            }
        }

        Object version = json.opt("version");
        if (version instanceof String) {
            status.version = (String) version;
        } else if (version instanceof JSONObject) {
            status.version = ((JSONObject) version).optString("name", "");
        }

        JSONObject motd = json.optJSONObject("motd");
        if (motd != null) {
            JSONArray clean = motd.optJSONArray("clean");
            if (clean != null) {
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < clean.length(); i++) {
                    if (i > 0) {
                        builder.append('\n');
                    }
                    builder.append(clean.optString(i, ""));
                }
                status.motd = builder.toString();
            } else {
                status.motd = motd.optString("clean", "");
            }
        }

        status.favicon = cleanIcon(json.optString("icon", ""));
        if (!status.online) {
            status.players.clear();
            status.onlineCount = 0;
        }
        return status;
    }

    private static ServerStatus base(String address) {
        ServerStatus status = new ServerStatus();
        status.address = address;
        status.hasData = true;
        status.updatedAt = System.currentTimeMillis();
        return status;
    }

    private static ServerStatus failure(String address, String error) {
        ServerStatus status = new ServerStatus();
        status.address = address;
        status.hasData = false;
        status.error = error;
        status.updatedAt = System.currentTimeMillis();
        return status;
    }

    private static String cleanIcon(String icon) {
        if (icon == null) {
            return "";
        }
        String value = icon.trim();
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma > 0) {
            value = value.substring(comma + 1);
        }
        if (value.length() > 60000) {
            return "";
        }
        return value;
    }

    private static String portOf(String address) {
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            if (close > 0 && address.length() > close + 2 && address.charAt(close + 1) == ':') {
                return address.substring(close + 2);
            }
            return "";
        }
        int first = address.indexOf(':');
        if (first > 0 && first == address.lastIndexOf(':')) {
            return address.substring(first + 1);
        }
        return "";
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception ignored) {
            return value;
        }
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MC-Monitor/1.0 (Android)");

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return null;
            }

            InputStream stream = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
                if (builder.length() > 1024 * 1024) {
                    break;
                }
            }
            reader.close();
            return new JSONObject(builder.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
