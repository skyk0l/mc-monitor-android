package com.mcmonitor.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Downloads verified APKs from this app's public GitHub Releases. */
public final class AppUpdateManager {
    private static final String REPOSITORY = "skyk0l/mc-monitor-android";
    private static final String API_URL =
            "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final String USER_AGENT = "MC-Monitor-Android-Updater";
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 45000;
    private static final long MAX_APK_SIZE = 100L * 1024L * 1024L;
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppUpdateManager() {
    }

    public static final class UpdateInfo {
        public final String version;
        public final String notes;
        private final String url;
        private final long size;
        private final String sha256;

        private UpdateInfo(String version, String notes, String url, long size, String sha256) {
            this.version = version;
            this.notes = notes;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public interface CheckCallback {
        void onLatest();
        void onUpdateAvailable(UpdateInfo update);
        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onDownloaded(File apk);
        void onError(String message);
    }

    public static void checkForUpdate(Context context, CheckCallback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                UpdateInfo update = fetchLatest();
                String currentVersion = installedVersion(appContext);
                boolean newer = compareVersions(update.version, currentVersion) > 0;
                MAIN.post(() -> {
                    if (newer) {
                        callback.onUpdateAvailable(update);
                    } else {
                        callback.onLatest();
                    }
                });
            } catch (Exception exception) {
                MAIN.post(() -> callback.onError(userMessage(exception)));
            }
        });
    }

    public static void download(
            Context context, UpdateInfo update, DownloadCallback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            File part = null;
            try {
                File directory = new File(appContext.getFilesDir(), "updates");
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IOException("Не удалось создать папку для обновления.");
                }
                File apk = new File(directory, "MC-Monitor-" + update.version + ".apk");
                part = new File(directory, apk.getName() + ".part");
                downloadAndVerify(update, part, callback);
                Files.move(
                        part.toPath(),
                        apk.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                part = null;
                MAIN.post(() -> callback.onDownloaded(apk));
            } catch (Exception exception) {
                if (part != null && part.exists() && !part.delete()) {
                    android.util.Log.w("MCMonitorUpdate", "Не удалось удалить частичный APK");
                }
                MAIN.post(() -> callback.onError(userMessage(exception)));
            }
        });
    }

    private static UpdateInfo fetchLatest() throws Exception {
        HttpURLConnection connection = openConnection(new URL(API_URL));
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub вернул HTTP " + status + ".");
            }
            JSONObject release;
            try (InputStream input = connection.getInputStream()) {
                byte[] body = readLimited(input, 2 * 1024 * 1024);
                release = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) {
                throw new IOException("GitHub не вернул стабильный релиз.");
            }

            String tag = release.optString("tag_name", "");
            String version = normalizeVersion(tag);
            String expectedName = "MC-Monitor-Android-v" + version + ".apk";
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) {
                throw new IOException("В релизе отсутствует APK.");
            }
            JSONObject asset = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject candidate = assets.optJSONObject(i);
                if (candidate != null && expectedName.equals(candidate.optString("name"))) {
                    asset = candidate;
                    break;
                }
            }
            if (asset == null) {
                throw new IOException("В релизе нет ожидаемого APK: " + expectedName);
            }

            String url = asset.optString("browser_download_url", "");
            String expectedPrefix = "https://github.com/" + REPOSITORY
                    + "/releases/download/" + tag + "/" + expectedName;
            if (!expectedPrefix.equals(url)) {
                throw new IOException("Ссылка на APK не соответствует официальному релизу.");
            }
            long size = asset.optLong("size", -1L);
            String digest = asset.optString("digest", "").toLowerCase(Locale.ROOT);
            if (size <= 0L || size > MAX_APK_SIZE
                    || !digest.matches("sha256:[0-9a-f]{64}")) {
                throw new IOException("У APK неверный размер или отсутствует SHA-256.");
            }
            return new UpdateInfo(
                    version,
                    release.optString("body", ""),
                    url,
                    size,
                    digest.substring("sha256:".length()));
        } finally {
            connection.disconnect();
        }
    }

    private static void downloadAndVerify(
            UpdateInfo update, File destination, DownloadCallback callback) throws Exception {
        HttpURLConnection connection = openConnection(new URL(update.url));
        try {
            connection.setRequestProperty("Accept", "application/octet-stream");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Не удалось скачать APK (HTTP " + status + ").");
            }
            String finalHost = connection.getURL().getHost().toLowerCase(Locale.ROOT);
            if (!"github.com".equals(finalHost)
                    && !"release-assets.githubusercontent.com".equals(finalHost)
                    && !"objects.githubusercontent.com".equals(finalHost)) {
                throw new IOException("GitHub перенаправил скачивание на неизвестный сервер.");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0L;
            int lastProgress = -1;
            byte[] buffer = new byte[32 * 1024];
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination, false)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size > MAX_APK_SIZE || size > update.size) {
                        throw new IOException("Скачанный APK превышает допустимый размер.");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    int progress = (int) Math.min(100L, size * 100L / update.size);
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        MAIN.post(() -> callback.onProgress(progress));
                    }
                }
                output.getFD().sync();
            }
            if (size != update.size || !toHex(digest.digest()).equals(update.sha256)) {
                throw new IOException("Размер или SHA-256 скачанного APK не совпал с релизом.");
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openConnection(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            throw new IOException("Обновления разрешены только по HTTPS.");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    private static int compareVersions(String first, String second) throws IOException {
        int[] left = versionParts(first);
        int[] right = versionParts(second);
        for (int i = 0; i < left.length; i++) {
            if (left[i] != right[i]) {
                return Integer.compare(left[i], right[i]);
            }
        }
        return 0;
    }

    private static String normalizeVersion(String tag) throws IOException {
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        versionParts(version);
        return version;
    }

    private static int[] versionParts(String version) throws IOException {
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (!matcher.matches()) {
            throw new IOException("Некорректный номер версии: " + version);
        }
        try {
            return new int[]{
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            };
        } catch (NumberFormatException exception) {
            throw new IOException("Некорректный номер версии: " + version, exception);
        }
    }

    private static String installedVersion(Context context) throws Exception {
        PackageInfo info;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    android.content.pm.PackageManager.PackageInfoFlags.of(0));
        } else {
            info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        }
        return info.versionName == null ? "0.0.0" : info.versionName;
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maxBytes) {
                throw new IOException("Ответ GitHub слишком большой.");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        }
        return result.toString();
    }

    private static String userMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty()
                ? "Не удалось выполнить обновление."
                : message;
    }
}
