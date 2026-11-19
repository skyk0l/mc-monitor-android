package com.mcmonitor.app;

import android.content.Context;

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
import java.util.UUID;

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

    public static synchronized int synchronizeServers(
            Context context, AccountSession session) throws Exception {
        Context app = context.getApplicationContext();
        String previousOwner = AccountSessionStore.lastOwnerId(app);
        List<ServerEntry> local = ServerStore.load(app);
        List<ServerEntry> remote = fetchServers(session);
        List<ServerEntry> merged;
        if (previousOwner != null && !previousOwner.equals(session.userId)) {
            AccountSessionStore.saveBaseServers(app, new ArrayList<>());
            merged = remote;
        } else {
            List<ServerEntry> base = previousOwner == null
                    ? null : AccountSessionStore.loadBaseServers(app);
            merged = mergeServers(base, local, remote);
        }
        List<ServerEntry> latestLocal = ServerStore.load(app);
        merged = mergeServers(local, latestLocal, merged);
        ServerStore.save(app, merged);
        AccountSessionStore.saveOwnerId(app, session.userId);
        saveServers(session, merged);
        AccountSessionStore.saveBaseServers(app, merged);
        AccountSessionStore.save(app, session);
        return merged.size();
    }

    public static PublicProfile fetchProfile(AccountSession session)
            throws IOException, JSONException {
        String userId = encode(session.userId);
        JSONArray rows = new JSONArray(requestWithSession(
                "/rest/v1/profiles?select=id,username,display_name,bio,avatar_path,share_servers"
                        + "&id=eq." + userId,
                "GET",
                null,
                session,
                null));
        if (rows.length() == 0 || rows.optJSONObject(0) == null) {
            throw new IOException(
                    "Профиль не найден. Проверь, что миграция профилей применена.");
        }
        return profileFrom(rows.optJSONObject(0));
    }

    public static PublicProfile updateProfile(
            AccountSession session,
            String displayName,
            String bio,
            boolean shareServers) throws IOException, JSONException {
        String cleanName = displayName == null ? "" : displayName.trim();
        String cleanBio = bio == null ? "" : bio.trim();
        if (cleanName.length() > 32) {
            throw new IllegalArgumentException(
                    "Отображаемое имя должно быть не длиннее 32 символов.");
        }
        if (cleanBio.length() > 160) {
            throw new IllegalArgumentException(
                    "Описание профиля должно быть не длиннее 160 символов.");
        }
        JSONObject body = new JSONObject();
        body.put("display_name", cleanName);
        body.put("bio", cleanBio);
        body.put("share_servers", shareServers);
        JSONArray rows = new JSONArray(requestWithSession(
                "/rest/v1/profiles?id=eq." + encode(session.userId),
                "PATCH",
                body,
                session,
                "return=representation"));
        if (rows.length() == 0 || rows.optJSONObject(0) == null) {
            throw new IOException("Не удалось сохранить профиль. Проверь права Supabase.");
        }
        return profileFrom(rows.optJSONObject(0));
    }

    public static List<PublicProfile> searchProfiles(
            AccountSession session, String query) throws IOException, JSONException {
        String term = validateSearchTerm(query);
        JSONObject body = new JSONObject();
        body.put("search_term", term);
        body.put("result_limit", 20);
        return parseProfiles(requestWithSession(
                "/rest/v1/rpc/search_public_profiles", "POST", body, session, null));
    }

    public static List<PublicProfile> listFollowedProfiles(AccountSession session)
            throws IOException, JSONException {
        JSONObject body = new JSONObject();
        body.put("result_limit", 50);
        return parseProfiles(requestWithSession(
                "/rest/v1/rpc/list_followed_profiles", "POST", body, session, null));
    }

    public static List<PublicServer> searchPublicServers(
            AccountSession session, String query) throws IOException, JSONException {
        String term = validateSearchTerm(query);
        JSONObject body = new JSONObject();
        body.put("search_term", term);
        body.put("result_limit", 30);
        return parsePublicServers(requestWithSession(
                "/rest/v1/rpc/search_public_servers", "POST", body, session, null), null);
    }

    public static List<PublicServer> listPublicServers(
            AccountSession session, String ownerId) throws IOException, JSONException {
        String target = validateUserId(ownerId);
        JSONObject body = new JSONObject();
        body.put("target_user", target);
        body.put("result_limit", 50);
        return parsePublicServers(requestWithSession(
                "/rest/v1/rpc/list_public_servers", "POST", body, session, null), target);
    }

    public static void followProfile(AccountSession session, String targetId)
            throws IOException, JSONException {
        String target = validateUserId(targetId);
        if (target.equals(session.userId)) {
            throw new IllegalArgumentException("Нельзя подписаться на собственный профиль.");
        }
        JSONObject body = new JSONObject();
        body.put("follower_id", session.userId);
        body.put("followed_id", target);
        requestWithSession(
                "/rest/v1/profile_follows?on_conflict=follower_id,followed_id",
                "POST",
                body,
                session,
                "resolution=ignore-duplicates,return=minimal");
    }

    public static void unfollowProfile(AccountSession session, String targetId)
            throws IOException, JSONException {
        String target = validateUserId(targetId);
        if (target.equals(session.userId)) {
            throw new IllegalArgumentException("Нельзя отписаться от собственного профиля.");
        }
        requestWithSession(
                "/rest/v1/profile_follows?follower_id=eq." + encode(session.userId)
                        + "&followed_id=eq." + encode(target),
                "DELETE",
                null,
                session,
                null);
    }

    public static PublicProfile uploadAvatar(AccountSession session, byte[] jpeg)
            throws IOException, JSONException {
        if (jpeg == null || jpeg.length < 3
                || (jpeg[0] & 0xff) != 0xff
                || (jpeg[1] & 0xff) != 0xd8
                || (jpeg[2] & 0xff) != 0xff) {
            throw new IllegalArgumentException("Аватар должен быть изображением JPEG.");
        }
        if (jpeg.length > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("Размер аватара не должен превышать 2 МБ.");
        }
        String userId = validateUserId(session.userId);
        requestBytesWithSession(
                "/storage/v1/object/avatars/" + userId + "/avatar.jpg",
                "POST",
                jpeg,
                session,
                "image/jpeg",
                "x-upsert",
                "true");
        JSONObject update = new JSONObject();
        update.put("avatar_path", userId + "/avatar.jpg");
        requestWithSession(
                "/rest/v1/profiles?id=eq." + encode(userId),
                "PATCH",
                update,
                session,
                "return=minimal");
        return fetchProfile(session);
    }

    public static String avatarUrl(PublicProfile profile) {
        if (profile == null || profile.avatarPath == null
                || !profile.avatarPath.equals(profile.userId + "/avatar.jpg")) {
            return null;
        }
        return PROJECT_URL + "/storage/v1/object/public/avatars/"
                + profile.userId + "/avatar.jpg";
    }

    public static byte[] downloadAvatar(PublicProfile profile) throws IOException {
        String avatarUrl = avatarUrl(profile);
        if (avatarUrl == null) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(avatarUrl).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "image/jpeg");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Не удалось загрузить аватар (HTTP " + status + ").");
            }
            try (InputStream input = connection.getInputStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > 2 * 1024 * 1024) {
                        throw new IOException("Размер аватара превышает 2 МБ.");
                    }
                    output.write(buffer, 0, read);
                }
                byte[] data = output.toByteArray();
                if (data.length < 3 || (data[0] & 0xff) != 0xff
                        || (data[1] & 0xff) != 0xd8 || (data[2] & 0xff) != 0xff) {
                    throw new IOException("Supabase вернул некорректный файл аватара.");
                }
                return data;
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String validateSearchTerm(String query) {
        String term = query == null ? "" : query.trim();
        if (term.length() < 2 || term.length() > 80) {
            throw new IllegalArgumentException("Для поиска введи от 2 до 80 символов.");
        }
        return term;
    }

    private static String validateUserId(String userId) {
        try {
            String normalized = UUID.fromString(userId).toString();
            if (!normalized.equalsIgnoreCase(userId)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return normalized;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("У профиля неверный идентификатор.", exception);
        }
    }

    private static List<PublicProfile> parseProfiles(String raw)
            throws IOException, JSONException {
        JSONArray rows = new JSONArray(raw);
        List<PublicProfile> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                throw new IOException("Supabase вернул профиль в неверном формате.");
            }
            result.add(profileFrom(row));
        }
        return result;
    }

    private static PublicProfile profileFrom(JSONObject row) throws IOException {
        String userId;
        try {
            userId = validateUserId(row.optString("id", ""));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Supabase вернул профиль с неверным идентификатором.", exception);
        }
        String username = row.optString("username", "");
        if (!username.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IOException("Supabase вернул профиль с неверным никнеймом.");
        }
        String avatarPath = row.isNull("avatar_path")
                ? null : row.optString("avatar_path", null);
        if (avatarPath != null && !avatarPath.equals(userId + "/avatar.jpg")) {
            throw new IOException("Supabase вернул небезопасный путь аватара.");
        }
        return new PublicProfile(
                userId,
                username,
                row.optString("display_name", ""),
                row.optString("bio", ""),
                avatarPath,
                row.optBoolean("share_servers", false),
                row.optBoolean("is_following", false));
    }

    private static List<PublicServer> parsePublicServers(String raw, String ownerFallback)
            throws IOException, JSONException {
        JSONArray rows = new JSONArray(raw);
        List<PublicServer> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                throw new IOException("Supabase вернул сервер в неверном формате.");
            }
            String ownerId = row.optString("owner_id", ownerFallback == null ? "" : ownerFallback);
            if (!ownerId.isEmpty()) {
                try {
                    ownerId = validateUserId(ownerId);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Supabase вернул сервер с неверным владельцем.", exception);
                }
            }
            String username = row.optString("username", "");
            String address = row.optString("server_address", "");
            if (!username.matches("[A-Za-z0-9_]{3,16}") || address.trim().isEmpty()) {
                throw new IOException("Supabase вернул сервер с неверными данными.");
            }
            String protocol = ServerEntry.normalizeProtocol(row.optString("protocol", "java"));
            result.add(new PublicServer(
                    ownerId,
                    username,
                    row.optString("display_name", ""),
                    row.optString("server_name", address),
                    address,
                    protocol));
        }
        return result;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void requestBytesWithSession(
            String path,
            String method,
            byte[] body,
            AccountSession session,
            String contentType,
            String headerName,
            String headerValue) throws IOException, JSONException {
        try {
            requestBytes(path, method, body, session, contentType, headerName, headerValue);
        } catch (CloudException exception) {
            if (exception.statusCode != HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw exception;
            }
            refreshSession(session);
            requestBytes(path, method, body, session, contentType, headerName, headerValue);
        }
    }

    private static void requestBytes(
            String path,
            String method,
            byte[] body,
            AccountSession session,
            String contentType,
            String headerName,
            String headerValue) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(PROJECT_URL + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("apikey", PUBLISHABLE_KEY);
            connection.setRequestProperty("Authorization", "Bearer " + session.accessToken);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", contentType);
            connection.setRequestProperty(headerName, headerValue);
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String response = stream == null ? "" : readUtf8(stream);
            if (status < 200 || status >= 300) {
                throw new CloudException(status, errorMessage(response));
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
                .equals(ServerEntry.normalizeProtocol(second.protocol))
                && first.showInWidget == second.showInWidget
                && safe(first.groupId).equals(safe(second.groupId))
                && prioritiesEqual(first.priorityPlayers, second.priorityPlayers);
    }

    private static ServerEntry copy(ServerEntry entry) {
        ServerEntry copy = new ServerEntry(entry.address, entry.name);
        copy.id = entry.id;
        copy.autoName = entry.autoName;
        copy.protocol = ServerEntry.normalizeProtocol(entry.protocol);
        copy.showInWidget = entry.showInWidget;
        copy.groupId = safe(entry.groupId);
        copy.priorityPlayers = new ArrayList<>(
                entry.priorityPlayers == null ? new ArrayList<>() : entry.priorityPlayers);
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
            entry.showInWidget = json.optBoolean("show_in_widget", false);
            Object rawGroupId = json.opt("group_id");
            entry.groupId = rawGroupId == null || JSONObject.NULL.equals(rawGroupId)
                    ? "" : rawGroupId.toString();
            JSONArray priorities = json.optJSONArray("priority_players");
            if (priorities != null) {
                for (int j = 0; j < priorities.length(); j++) {
                    String player = priorities.optString(j, "").trim();
                    if (!player.isEmpty() && player.length() <= 32
                            && !containsIgnoreCase(entry.priorityPlayers, player)) {
                        entry.priorityPlayers.add(player);
                    }
                }
            }
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
            json.put("show_in_widget", entry.showInWidget);
            json.put("group_id", safe(entry.groupId));
            JSONArray priorities = new JSONArray();
            if (entry.priorityPlayers != null) {
                for (String player : entry.priorityPlayers) {
                    if (player != null && !player.trim().isEmpty()) {
                        priorities.put(player.trim());
                    }
                }
            }
            json.put("priority_players", priorities);
            array.put(json);
        }
        return array;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean prioritiesEqual(List<String> first, List<String> second) {
        List<String> left = first == null ? new ArrayList<>() : first;
        List<String> right = second == null ? new ArrayList<>() : second;
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).equalsIgnoreCase(right.get(i))) {
                return false;
            }
        }
        return true;
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

    public static final class PublicProfile {
        public final String userId;
        public final String username;
        public final String displayName;
        public final String bio;
        public final String avatarPath;
        public final boolean shareServers;
        public final boolean isFollowing;

        private PublicProfile(
                String userId,
                String username,
                String displayName,
                String bio,
                String avatarPath,
                boolean shareServers,
                boolean isFollowing) {
            this.userId = userId;
            this.username = username;
            this.displayName = displayName;
            this.bio = bio;
            this.avatarPath = avatarPath;
            this.shareServers = shareServers;
            this.isFollowing = isFollowing;
        }
    }

    public static final class PublicServer {
        public final String ownerId;
        public final String username;
        public final String displayName;
        public final String name;
        public final String address;
        public final String protocol;

        private PublicServer(
                String ownerId,
                String username,
                String displayName,
                String name,
                String address,
                String protocol) {
            this.ownerId = ownerId;
            this.username = username;
            this.displayName = displayName;
            this.name = name;
            this.address = address;
            this.protocol = protocol;
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
