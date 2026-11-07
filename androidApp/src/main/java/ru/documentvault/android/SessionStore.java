package ru.documentvault.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted authentication session; the vault key and account passwords are never stored here. */
final class SessionStore {
    private static final String ALIAS = "documentvault.session.v1";
    private final SharedPreferences preferences;
    private final String alias;
    SessionStore(Context context) { this(context, "session", ALIAS); }
    SessionStore(Context context, String preferencesName, String alias) { preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE); this.alias = alias; }
    static final class Session {
        final String address, username, token;
        Session(String address, String username, String token) { this.address = address; this.username = username; this.token = token; }
    }
    private SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey existing = (SecretKey) store.getKey(alias, null);
        if (existing != null || !create) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    void save(Session session) throws Exception {
        byte[] plain = new JSONObject().put("address", session.address).put("username", session.username).put("token", session.token).toString().getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain);
            if (iv.length != 12) throw new IllegalStateException("Некорректный nonce сессии");
            byte[] data = new byte[13 + encrypted.length]; data[0] = 1;
            System.arraycopy(iv, 0, data, 1, 12); System.arraycopy(encrypted, 0, data, 13, encrypted.length);
            if (!preferences.edit().putString("data", Base64.getEncoder().encodeToString(data)).commit()) throw new IllegalStateException("Не удалось сохранить сессию");
        } finally { Arrays.fill(plain, (byte) 0); }
    }
    Session load() throws Exception {
        String encoded = preferences.getString("data", null); if (encoded == null) return null;
        byte[] data = Base64.getDecoder().decode(encoded);
        if (data.length < 29 || data[0] != 1) throw new IllegalStateException("Повреждена сессия");
        SecretKey key = key(false); if (key == null) throw new IllegalStateException("Ключ сессии недоступен");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOfRange(data, 1, 13)));
        byte[] plain = cipher.doFinal(data, 13, data.length - 13);
        try { JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8)); return new Session(json.getString("address"), json.getString("username"), json.getString("token")); }
        finally { Arrays.fill(plain, (byte) 0); }
    }
    void clear() { preferences.edit().remove("data").apply(); }
}
