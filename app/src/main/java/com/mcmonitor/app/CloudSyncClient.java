package com.mcmonitor.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Supabase Auth and row-level-secured server-list storage. */
public final class CloudSyncClient {
    private static final String PROJECT_URL = "https://yjxacgziailzkzghmxgi.supabase.co";
    private static final String PUBLISHABLE_KEY = "sb_publishable_-6kGEzd6QYBe0B7ww1kODw_HC6wg789";
    private static final int TIMEOUT_MS = 12_000;

    private CloudSyncClient() {
    }

    public static AccountSession signUp(String username, String password)
            throws IOException, JSONException {
        validateCredentials(username, password);
        JSONObject body = new JSONObject();
        body.put("email", syntheticEmail(username));
        body.put("password", password);
        JSONObject metadata = new JSONObject();
        metadata.put("username", username.trim());
        body.put("data", metadata);
        JSONObject response = request("/auth/v1/signup", "POST", body, null, null);
        return sessionFrom(response, username.trim());
    }

    public static AccountSession signIn(String username, String password)
            throws IOException, JSONException {
        validateCredentials(username, password);
        JSONObject body = new JSONObject();
        body.put("email", syntheticEmail(username));
        body.put("password", password);
        JSONObject response = request(
                "/auth/v1/token?grant_type=password", "POST", body, null, null);
        return sessionFrom(response, username.trim());
    }

    public static List<ServerEntry> fetchServers(AccountSession session)
            throws IOException, JSONException {
        String userId = URLEncoder.encode(session.userId, StandardCharsets.UTF_8.name());
        String response = requestWithSession(
                "/rest/v1/server_lists?select=servers&user_id=eq." + userId,
                "GET",
                null,
                session,
                null);
        JSONArray rows = new JSONArray(response);
        if (rows.length() == 0) {
            return new ArrayList<>();
        }
        JSONObject row = rows.optJSONObject(0);
        if (row == null) {
            throw new IOException("Облачный список серверов имеет неверный формат.");
        }
        JSONArray servers = row.optJSONArray("servers");
        return parseServers(servers);
    }

    public static void saveServers(
            AccountSession session, List<ServerEntry> servers) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("user_id", session.userId);
        body.put("servers", toCloudArray(servers));
        requestWithSession(
                "/rest/v1/server_lists?on_conflict=user_id",
                "POST",
                body,
                session,
                "resolution=merge-duplicates,return=minimal");
    }

    public static String syntheticEmail(String username) {
        return username.trim().toLowerCase(Locale.ROOT) + "@users.mcmonitor.invalid";
    }

    public static void validateCredentials(String username, String password) {
        String value = username == null ? "" : username.trim();
        if (!value.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Никнейм: 3–16 латинских букв, цифр или _.");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Пароль должен содержать минимум 8 символов.");
        }
    }

    public static List<ServerEntry> mergeServers(
            List<ServerEntry> base,
            List<ServerEntry> local,
            List<ServerEntry> remote) {
        if (base == null) {
            return union(local, remote);
        }
        Map<String, ServerEntry> baseMap = byAddress(base);
        Map<String, ServerEntry> localMap = byAddress(local);
        Map<String, ServerEntry> remoteMap = byAddress(remote);
        Set<String> addresses = new LinkedHashSet<>();
        addresses.addAll(baseMap.keySet());
        addresses.addAll(localMap.keySet());
        addresses.addAll(remoteMap.keySet());

        List<ServerEntry> merged = new ArrayList<>();
        for (String address : addresses) {
            ServerEntry baseEntry = baseMap.get(address);
            ServerEntry localEntry = localMap.get(address);
            ServerEntry remoteEntry = remoteMap.get(address);
            boolean localChanged = !sameServer(baseEntry, localEntry);
            boolean remoteChanged = !sameServer(baseEntry, remoteEntry);
            ServerEntry selected;
            if (localChanged) {
                selected = localEntry;
            } else if (remoteChanged) {
                selected = remoteEntry;
            } else {
                selected = baseEntry;
            }
            if (selected != null) {
                merged.add(copy(selected));
            }
        }
        return merged;
    }

    private static List<ServerEntry> union(
            List<ServerEntry> local, List<ServerEntry> remote) {
        Map<String, ServerEntry> result = byAddress(local);
        for (ServerEntry entry : remote) {
            result.putIfAbsent(entry.address, copy(entry));
        }
        return new ArrayList<>(result.values());
    }

    private static Map<String, ServerEntry> byAddress(List<ServerEntry> entries) {
        Map<String, ServerEntry> result = new LinkedHashMap<>();
        for (ServerEntry entry : entries) {
            result.put(entry.address, copy(entry));
        }
        return result;
    }

    private static boolean sameServer(ServerEntry first, ServerEntry second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.address.equals(second.address)
                && (first.name == null ? "" : first.name)
                .equals(second.name == null ? "" : second.name)
                && ServerEntry.normalizeProtocol(first.protocol)
                .equals(ServerEntry.normalizeProtocol(second.protocol));
    }

    private static ServerEntry copy(ServerEntry entry) {
        ServerEntry copy = new ServerEntry(entry.address, entry.name);
        copy.id = entry.id;
        copy.autoName = entry.autoName;
        copy.protocol = ServerEntry.normalizeProtocol(entry.protocol);
        return copy;
    }

    private static List<ServerEntry> parseServers(JSONArray array) throws IOException {
        if (array == null) {
            throw new IOException("Облачный список серверов имеет неверный формат.");
        }
        List<ServerEntry> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.optJSONObject(i);
            if (json == null) {
                throw new IOException("В облачном списке есть некорректная запись.");
            }
            String address = json.optString("address", "");
            if (address.isEmpty()) {
                throw new IOException("В облачном списке есть сервер без адреса.");
            }
            ServerEntry entry = new ServerEntry(address, json.optString("name", ""));
            entry.protocol = ServerEntry.normalizeProtocol(
                    json.optString("protocol", ServerEntry.PROTOCOL_JAVA));
            result.add(entry);
        }
        return result;
    }

    private static JSONArray toCloudArray(List<ServerEntry> servers) throws JSONException {
        JSONArray array = new JSONArray();
        for (ServerEntry entry : servers) {
            JSONObject json = new JSONObject();
            json.put("address", ServerEntry.normalize(entry.address));
            json.put("name", entry.displayName());
            json.put("protocol", ServerEntry.normalizeProtocol(entry.protocol));
            array.put(json);
        }
        return array;
    }

    private static AccountSession sessionFrom(JSONObject response, String fallbackUsername)
            throws IOException {
        String access = response.optString("access_token", "");
        String refresh = response.optString("refresh_token", "");
        JSONObject user = response.optJSONObject("user");
        if (access.isEmpty() || refresh.isEmpty() || user == null) {
            throw new IOException(
                    "В Supabase отключи подтверждение email: Authentication → Providers → Email.");
        }
        JSONObject metadata = user.optJSONObject("user_metadata");
        String username = metadata == null
                ? fallbackUsername
                : metadata.optString("username", fallbackUsername);
        String userId = user.optString("id", "");
        if (userId.isEmpty()) {
            throw new IOException("Supabase не вернул идентификатор аккаунта.");
        }
        return new AccountSession(access, refresh, userId, username);
    }

    private static String requestWithSession(
            String path,
            String method,
            JSONObject body,
            AccountSession session,
            String prefer) throws IOException, JSONException {
        try {
            return requestText(path, method, body, session.accessToken, prefer);
        } catch (CloudException exception) {
            if (exception.statusCode != HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw exception;
            }
            refreshSession(session);
            return requestText(path, method, body, session.accessToken, prefer);
        }
    }

    private static void refreshSession(AccountSession session) throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("refresh_token", session.refreshToken);
        JSONObject response = request(
                "/auth/v1/token?grant_type=refresh_token", "POST", body, null, null);
        AccountSession updated = sessionFrom(response, session.username);
        session.accessToken = updated.accessToken;
        session.refreshToken = updated.refreshToken;
        session.userId = updated.userId;
        session.username = updated.username;
    }

    private static JSONObject request(
            String path,
            String method,
            JSONObject body,
            String accessToken,
            String prefer) throws IOException, JSONException {
        String raw = requestText(path, method, body, accessToken, prefer);
        return raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
    }

    private static String requestText(
            String path,
            String method,
            JSONObject body,
            String accessToken,
            String prefer) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(PROJECT_URL + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("apikey", PUBLISHABLE_KEY);
            connection.setRequestProperty("Accept", "application/json");
            if (accessToken != null && !accessToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            }
            if (prefer != null) {
                connection.setRequestProperty("Prefer", prefer);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = stream == null ? "" : readUtf8(stream);
            if (status < 200 || status >= 300) {
                throw new CloudException(status, errorMessage(response));
            }
            return response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String errorMessage(String response) {
        try {
            JSONObject json = new JSONObject(response);
            String message = json.optString("msg",
                    json.optString("message",
                            json.optString("error_description", json.optString("error", ""))));
            return message.isEmpty() ? response : message;
        } catch (JSONException ignored) {
            return response.isEmpty() ? "Ошибка соединения с Supabase" : response;
        }
    }

    private static final class CloudException extends IOException {
        final int statusCode;

        CloudException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}
