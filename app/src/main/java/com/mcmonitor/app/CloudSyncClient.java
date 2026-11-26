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
        String identifier = validateLoginIdentifier(username, password);
        JSONObject body = new JSONObject();
        body.put("email", identifier.indexOf('@') >= 0
                ? identifier : syntheticEmail(identifier));
        body.put("password", password);
        JSONObject response = request(
                "/auth/v1/token?grant_type=password", "POST", body, null, null);
        String fallbackUsername = identifier.indexOf('@') >= 0
                ? identifier.substring(0, identifier.indexOf('@')) : identifier;
        return sessionFrom(response, fallbackUsername);
    }

    public static void requestEmailSignupCode(String email)
            throws IOException, JSONException {
        String cleanEmail = validateEmail(email);
        JSONObject body = new JSONObject();
        body.put("email", cleanEmail);
        body.put("create_user", true);
        request("/auth/v1/otp", "POST", body, null, null);
    }

    public static AccountSession completeEmailSignup(
            String email, String code, String username, String password)
            throws IOException, JSONException {
        String cleanEmail = validateEmail(email);
        validateCredentials(username, password);
        String cleanCode = code == null ? "" : code.trim();
        if (!cleanCode.matches("[0-9]{4,10}")) {
            throw new IllegalArgumentException("Введи код из письма.");
        }

        JSONObject verifyBody = new JSONObject();
        verifyBody.put("email", cleanEmail);
        verifyBody.put("token", cleanCode);
        verifyBody.put("type", "signup");
        AccountSession session = sessionFrom(
                request("/auth/v1/verify", "POST", verifyBody, null, null),
                username.trim());

        JSONObject userBody = new JSONObject();
        userBody.put("password", password);
        JSONObject metadata = new JSONObject();
        metadata.put("username", username.trim());
        userBody.put("data", metadata);
        requestWithSession("/auth/v1/user", "PUT", userBody, session, null);

        JSONObject profileBody = new JSONObject();
        profileBody.put("target_username", username.trim());
        requestWithSession(
                "/rest/v1/rpc/complete_email_registration",
                "POST",
                profileBody,
                session,
                null);
        session.username = username.trim();
        return session;
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

    public static long addServerToCatalog(
            String address, String name, String protocol) throws IOException, JSONException {
        String cleanAddress = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        String cleanName = name == null ? "" : name.trim();
        String cleanProtocol = protocol == null
                ? "java" : protocol.trim().toLowerCase(Locale.ROOT);
        if (!"java".equals(cleanProtocol) && !"bedrock".equals(cleanProtocol)) {
            throw new IllegalArgumentException("Выбран неизвестный протокол сервера.");
        }
        if (cleanAddress.length() < 1 || cleanAddress.length() > 253
                || !cleanAddress.matches("[a-z0-9._:-]+")) {
            throw new IllegalArgumentException("Укажи корректный адрес сервера.");
        }
        if (cleanName.length() > 60) {
            throw new IllegalArgumentException(
                    "Название сервера не должно превышать 60 символов.");
        }
        JSONObject body = new JSONObject();
        body.put("server_address", cleanAddress);
        body.put("server_name", cleanName);
        body.put("server_protocol", cleanProtocol);
        String raw = requestText(
                "/rest/v1/rpc/add_server_to_catalog", "POST", body, null, null).trim();
        try {
            long id = Long.parseLong(raw);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // Report the malformed API response below.
        }
        throw new IOException("Каталог серверов вернул неверный идентификатор.");
    }

    public static List<CatalogServer> listServerCatalog(
            String query, String sortBy, int limit, AccountSession session)
            throws IOException, JSONException {
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.length() > 80) {
            throw new IllegalArgumentException("Поисковый запрос слишком длинный.");
        }
        if (!"votes".equals(sortBy)
                && !"newest".equals(sortBy)
                && !"subscribers".equals(sortBy)
                && !"online".equals(sortBy)) {
            throw new IllegalArgumentException("Неизвестный порядок каталога серверов.");
        }
        JSONObject body = new JSONObject();
        body.put("search_term", cleanQuery);
        body.put("sort_by", sortBy);
        body.put("result_limit", Math.max(1, Math.min(limit, 100)));
        String path = "/rest/v1/rpc/list_public_server_catalog";
        String raw = session == null
                ? requestText(path, "POST", body, null, null)
                : requestWithSession(path, "POST", body, session, null);
        JSONArray rows = new JSONArray(raw);
        List<CatalogServer> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                throw new IOException("Supabase вернул запись каталога в неверном формате.");
            }
            long id = row.optLong("id", 0);
            String address = row.optString("address", "").trim();
            String protocol = row.optString("protocol", "").toLowerCase(Locale.ROOT);
            int votes = row.optInt("votes", -1);
            int subscribers = row.optInt("subscribers", -1);
            int onlineCount = row.optInt("online_count", -1);
            String version = row.optString("version", "");
            String motd = row.optString("motd", "");
            String favicon = row.optString("favicon", "");
            if (id <= 0 || address.isEmpty()
                    || !address.matches("[A-Za-z0-9._:-]{1,253}")
                    || votes < 0 || subscribers < 0 || onlineCount < -1
                    || version.length() > 100 || motd.length() > 2000
                    || favicon.length() > 60000
                    || (!favicon.isEmpty()
                    && !favicon.matches("[A-Za-z0-9+/]+={0,2}"))) {
                throw new IOException("Supabase вернул некорректный сервер каталога.");
            }
            if (!"java".equals(protocol) && !"bedrock".equals(protocol)) {
                throw new IOException("Supabase вернул неизвестный протокол сервера.");
            }
            result.add(new CatalogServer(
                    id,
                    row.optString("name", ""),
                    address,
                    protocol,
                    votes,
                    subscribers,
                    onlineCount,
                    row.optBoolean("has_voted_today", false),
                    version,
                    motd,
                    favicon));
        }
        return result;
    }

    public static void reportServerCatalogStatus(
            AccountSession session, String address, ServerStatus status)
            throws IOException, JSONException {
        if (session == null || status == null || !status.hasData) {
            throw new IllegalArgumentException("Для отчёта нужен подтверждённый статус сервера.");
        }
        String cleanAddress = ServerEntry.normalize(address).toLowerCase(Locale.ROOT);
        if (cleanAddress.length() < 1 || cleanAddress.length() > 253
                || !cleanAddress.matches("[a-z0-9._:-]+")) {
            throw new IllegalArgumentException("Укажи корректный адрес сервера.");
        }
        if (status.onlineCount < 0 || status.maxCount < 0
                || (status.maxCount > 0 && status.onlineCount > status.maxCount)) {
            throw new IllegalArgumentException("Счётчик игроков имеет неверный формат.");
        }
        JSONArray players = new JSONArray();
        if (status.online) {
            for (String player : status.players) {
                if (players.length() == 100) {
                    break;
                }
                String cleanPlayer = player == null ? "" : player.trim();
                if (!cleanPlayer.isEmpty() && cleanPlayer.length() <= 64) {
                    players.put(cleanPlayer);
                }
            }
        }
        JSONObject body = new JSONObject();
        body.put("server_address", cleanAddress);
        body.put("is_online", status.online);
        body.put("player_count", status.online ? status.onlineCount : 0);
        body.put("max_players", status.maxCount);
        body.put("player_names", players);
        String version = checkedStatusText(status.version, 100, "Версия сервера");
        String motd = checkedStatusText(status.motd, 2000, "Описание сервера");
        String icon = checkedStatusText(status.favicon, 60000, "Иконка сервера");
        if (!icon.isEmpty() && !icon.matches("[A-Za-z0-9+/]+={0,2}")) {
            throw new IllegalArgumentException("Иконка сервера имеет неверный формат.");
        }
        body.put("server_version", version);
        body.put("server_motd", motd);
        body.put("server_icon", icon);
        requestWithSession(
                "/rest/v1/rpc/report_server_catalog_status",
                "POST",
                body,
                session,
                null);
    }

    private static String checkedStatusText(String value, int maxLength, String label) {
        if (value == null) {
            return "";
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(label + " слишком длинная.");
        }
        return value.trim();
    }

    public static List<CatalogHistorySample> listServerCatalogHistory(
            long serverId, int limit, AccountSession session)
            throws IOException, JSONException {
        if (serverId <= 0) {
            throw new IllegalArgumentException("Выбран неверный сервер.");
        }
        JSONObject body = new JSONObject();
        body.put("target_server_id", serverId);
        body.put("result_limit", Math.max(1, Math.min(limit, 500)));
        String path = "/rest/v1/rpc/get_server_catalog_history";
        String raw = session == null
                ? requestText(path, "POST", body, null, null)
                : requestWithSession(path, "POST", body, session, null);
        JSONArray rows = new JSONArray(raw);
        List<CatalogHistorySample> result = new ArrayList<>();
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) {
                throw new IOException("История сервера имеет неверный формат.");
            }
            String sampledAt = row.optString("sampled_at", "");
            int onlineCount = row.optInt("online_count", -1);
            int maxCount = row.optInt("max_count", -1);
            JSONArray playerRows = row.optJSONArray("players");
            if (sampledAt.isEmpty() || onlineCount < 0 || maxCount < 0
                    || (maxCount > 0 && onlineCount > maxCount)
                    || playerRows == null || playerRows.length() > 100) {
                throw new IOException("История сервера содержит некорректную запись.");
            }
            List<String> players = new ArrayList<>();
            for (int playerIndex = 0; playerIndex < playerRows.length(); playerIndex++) {
                String player = playerRows.optString(playerIndex, "").trim();
                if (!player.isEmpty() && player.length() <= 64) {
                    players.add(player);
                }
            }
            result.add(new CatalogHistorySample(
                    sampledAt, onlineCount, maxCount, players));
        }
        return result;
    }

    public static boolean voteForCatalogServer(AccountSession session, long serverId)
            throws IOException, JSONException {
        if (session == null || serverId <= 0) {
            throw new IllegalArgumentException("Войди в аккаунт, чтобы голосовать.");
        }
        JSONObject body = new JSONObject();
        body.put("server_id", serverId);
        String raw = requestWithSession(
                "/rest/v1/rpc/vote_for_catalog_server",
                "POST",
                body,
                session,
                null).trim();
        if ("true".equals(raw)) {
            return true;
        }
        if ("false".equals(raw)) {
            return false;
        }
        throw new IOException("Голосование вернуло неверный ответ.");
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
                "/rest/v1/profiles?select=id,username,display_name,bio,avatar_path,share_servers,"
                        + "share_followers,share_friends,share_subscriptions"
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

    public static PublicProfile fetchPublicProfile(AccountSession session, String targetId)
            throws IOException, JSONException {
        String target = validateUserId(targetId);
        JSONArray rows = new JSONArray(requestWithSession(
                "/rest/v1/profiles?select=id,username,display_name,bio,avatar_path,share_servers,"
                        + "share_followers,share_friends,share_subscriptions"
                        + "&id=eq." + encode(target),
                "GET",
                null,
                session,
                null));
        if (rows.length() == 0 || rows.optJSONObject(0) == null) {
            throw new IOException("Этот профиль больше недоступен.");
        }
        return profileFrom(rows.optJSONObject(0));
    }

    public static PublicProfile updateProfile(
            AccountSession session,
            String displayName,
            String bio,
            boolean shareServers) throws IOException, JSONException {
        return updateProfile(
                session, displayName, bio, shareServers, false, false, false);
    }

    public static PublicProfile updateProfile(
            AccountSession session,
            String displayName,
            String bio,
            boolean shareServers,
            boolean shareFollowers,
            boolean shareFriends,
            boolean shareSubscriptions) throws IOException, JSONException {
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
        body.put("share_followers", shareFollowers);
        body.put("share_friends", shareFriends);
        body.put("share_subscriptions", shareSubscriptions);
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

    public static ProfileSocialSummary getProfileSocialSummary(
            AccountSession session, String targetId) throws IOException, JSONException {
        String target = validateUserId(targetId);
        JSONObject body = new JSONObject();
        body.put("target_user", target);
        JSONArray rows = new JSONArray(requestWithSession(
                "/rest/v1/rpc/get_profile_social_summary", "POST", body, session, null));
        if (rows.length() != 1 || rows.optJSONObject(0) == null) {
            throw new IOException("Supabase не вернул данные социальных связей профиля.");
        }
        JSONObject row = rows.optJSONObject(0);
        return new ProfileSocialSummary(
                checkedCount(row, "followers_count"),
                checkedCount(row, "subscriptions_count"),
                checkedCount(row, "friends_count"),
                row.optBoolean("is_following", false),
                row.optBoolean("is_followed_by", false),
                row.optBoolean("can_view_servers", false),
                row.optBoolean("can_view_followers", false),
                row.optBoolean("can_view_friends", false),
                row.optBoolean("can_view_subscriptions", false));
    }

    public static List<PublicProfile> listProfileRelationships(
            AccountSession session, String targetId, String relationship)
            throws IOException, JSONException {
        String target = validateUserId(targetId);
        if (!"followers".equals(relationship)
                && !"friends".equals(relationship)
                && !"subscriptions".equals(relationship)) {
            throw new IllegalArgumentException("Неизвестный тип списка профиля.");
        }
        JSONObject body = new JSONObject();
        body.put("target_user", target);
        body.put("relationship", relationship);
        body.put("result_limit", 50);
        return parseProfiles(requestWithSession(
                "/rest/v1/rpc/list_profile_relationships", "POST", body, session, null));
    }

    private static int checkedCount(JSONObject row, String key) throws IOException {
        Object value = row.opt(key);
        if (!(value instanceof Number) || ((Number) value).longValue() < 0
                || ((Number) value).longValue() > Integer.MAX_VALUE) {
            throw new IOException("Supabase вернул неверный счётчик профиля.");
        }
        return ((Number) value).intValue();
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
                row.optBoolean("is_following", false),
                row.optBoolean("share_followers", false),
                row.optBoolean("share_friends", false),
                row.optBoolean("share_subscriptions", false),
                row.optBoolean("is_followed_by", false));
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

    public static String validateEmail(String email) {
        String clean = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (clean.length() > 254
                || !clean.matches("^[^@\\s]{1,64}@[^@\\s.]+(?:\\.[^@\\s.]+)+$")) {
            throw new IllegalArgumentException("Укажи корректный адрес электронной почты.");
        }
        return clean;
    }

    public static String validateLoginIdentifier(String identifier, String password) {
        String value = identifier == null ? "" : identifier.trim();
        if (value.indexOf('@') >= 0) {
            value = validateEmail(value);
        } else if (!value.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException(
                    "Введи корректный никнейм или адрес электронной почты.");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Пароль должен содержать минимум 8 символов.");
        }
        return value;
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
            throw new IOException("Supabase не вернул сессию. Проверь подтверждение email и OTP.");
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
        public final boolean shareFollowers;
        public final boolean shareFriends;
        public final boolean shareSubscriptions;
        public final boolean isFollowedBy;

        private PublicProfile(
                String userId,
                String username,
                String displayName,
                String bio,
                String avatarPath,
                boolean shareServers,
                boolean isFollowing,
                boolean shareFollowers,
                boolean shareFriends,
                boolean shareSubscriptions,
                boolean isFollowedBy) {
            this.userId = userId;
            this.username = username;
            this.displayName = displayName;
            this.bio = bio;
            this.avatarPath = avatarPath;
            this.shareServers = shareServers;
            this.isFollowing = isFollowing;
            this.shareFollowers = shareFollowers;
            this.shareFriends = shareFriends;
            this.shareSubscriptions = shareSubscriptions;
            this.isFollowedBy = isFollowedBy;
        }
    }

    public static final class ProfileSocialSummary {
        public final int followersCount;
        public final int subscriptionsCount;
        public final int friendsCount;
        public final boolean isFollowing;
        public final boolean isFollowedBy;
        public final boolean canViewServers;
        public final boolean canViewFollowers;
        public final boolean canViewFriends;
        public final boolean canViewSubscriptions;

        private ProfileSocialSummary(
                int followersCount,
                int subscriptionsCount,
                int friendsCount,
                boolean isFollowing,
                boolean isFollowedBy,
                boolean canViewServers,
                boolean canViewFollowers,
                boolean canViewFriends,
                boolean canViewSubscriptions) {
            this.followersCount = followersCount;
            this.subscriptionsCount = subscriptionsCount;
            this.friendsCount = friendsCount;
            this.isFollowing = isFollowing;
            this.isFollowedBy = isFollowedBy;
            this.canViewServers = canViewServers;
            this.canViewFollowers = canViewFollowers;
            this.canViewFriends = canViewFriends;
            this.canViewSubscriptions = canViewSubscriptions;
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

    public static final class CatalogServer {
        public final long id;
        public final String name;
        public final String address;
        public final String protocol;
        public final int votes;
        public final int subscribers;
        public final int onlineCount;
        public final boolean hasVotedToday;
        public final String version;
        public final String motd;
        public final String favicon;

        private CatalogServer(
                long id,
                String name,
                String address,
                String protocol,
                int votes,
                int subscribers,
                int onlineCount,
                boolean hasVotedToday,
                String version,
                String motd,
                String favicon) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.protocol = protocol;
            this.votes = votes;
            this.subscribers = subscribers;
            this.onlineCount = onlineCount;
            this.hasVotedToday = hasVotedToday;
            this.version = version;
            this.motd = motd;
            this.favicon = favicon;
        }
    }

    public static final class CatalogHistorySample {
        public final String sampledAt;
        public final int onlineCount;
        public final int maxCount;
        public final List<String> players;

        private CatalogHistorySample(
                String sampledAt, int onlineCount, int maxCount, List<String> players) {
            this.sampledAt = sampledAt;
            this.onlineCount = onlineCount;
            this.maxCount = maxCount;
            this.players = players;
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
