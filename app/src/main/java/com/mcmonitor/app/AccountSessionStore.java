package com.mcmonitor.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Encrypts refresh/access tokens with a key kept in Android Keystore. */
public final class AccountSessionStore {
    private static final String TAG = "MCMonitorAccount";
    private static final String PREFS = "account_sync";
    private static final String KEY_SESSION = "encrypted_session";
    private static final String KEY_OWNER = "last_owner_id";
    private static final String KEY_BASE = "last_synced_servers";
    private static final String KEY_ALIAS = "mcmonitor_account_session";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";

    private AccountSessionStore() {
    }

    public static synchronized void save(Context context, AccountSession session) throws Exception {
        JSONObject json = new JSONObject();
        json.put("access_token", session.accessToken);
        json.put("refresh_token", session.refreshToken);
        json.put("user_id", session.userId);
        json.put("username", session.username);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(cipher.getIV().length + encrypted.length);
        payload.put(cipher.getIV());
        payload.put(encrypted);
        String encoded = Base64.encodeToString(payload.array(), Base64.NO_WRAP);
        prefs(context).edit().putString(KEY_SESSION, encoded).apply();
    }

    public static synchronized AccountSession load(Context context) {
        String encoded = prefs(context).getString(KEY_SESSION, null);
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
            if (payload.length <= 12) {
                throw new IllegalStateException("Некорректная сессия");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
            JSONObject json = new JSONObject(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            return new AccountSession(
                    json.getString("access_token"),
                    json.getString("refresh_token"),
                    json.getString("user_id"),
                    json.optString("username", "Аккаунт"));
        } catch (Exception exception) {
            Log.w(TAG, "Не удалось открыть сохранённую сессию", exception);
            clearSession(context);
            return null;
        }
    }

    public static synchronized void clearSession(Context context) {
        prefs(context).edit().remove(KEY_SESSION).apply();
    }

    public static synchronized String lastOwnerId(Context context) {
        return prefs(context).getString(KEY_OWNER, null);
    }

    public static synchronized void saveOwnerId(Context context, String userId) {
        prefs(context).edit().putString(KEY_OWNER, userId).apply();
    }

    public static synchronized List<ServerEntry> loadBaseServers(Context context)
            throws JSONException {
        String raw = prefs(context).getString(KEY_BASE, null);
        if (raw == null) {
            return null;
        }
        JSONArray array = new JSONArray(raw);
        List<ServerEntry> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            ServerEntry entry = ServerEntry.fromJson(item);
            if (entry == null) {
                throw new JSONException("Некорректный сервер в снимке синхронизации");
            }
            result.add(entry);
        }
        return result;
    }

    public static synchronized void saveBaseServers(Context context, List<ServerEntry> servers) {
        JSONArray array = new JSONArray();
        for (ServerEntry entry : servers) {
            JSONObject json = new JSONObject();
            try {
                json.put("address", entry.address);
                json.put("name", entry.name == null ? "" : entry.name);
                array.put(json);
            } catch (JSONException exception) {
                Log.w(TAG, "Не удалось сохранить сервер в снимке синхронизации", exception);
            }
        }
        prefs(context).edit().putString(KEY_BASE, array.toString()).apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
