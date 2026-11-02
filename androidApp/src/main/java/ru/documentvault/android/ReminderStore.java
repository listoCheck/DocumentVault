package ru.documentvault.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local background-only schedule, encrypted using a device key that does not require unlock. */
final class ReminderStore {
    private static final String ALIAS = "documentvault.reminders.v1";
    private static final Object MUTEX = new Object();
    private final SharedPreferences preferences;
    ReminderStore(Context context) { preferences = context.getSharedPreferences("reminders", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;
        if (preferences.contains("data")) throw new IllegalStateException("Ключ напоминаний недоступен");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
    private JSONObject read() throws Exception {
        String encoded = preferences.getString("data", null);
        if (encoded == null) return new JSONObject();
        byte[] data = Base64.getDecoder().decode(encoded);
        if (data.length < 29 || data[0] != 1) throw new IllegalStateException("Повреждены данные напоминаний");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, java.util.Arrays.copyOfRange(data, 1, 13)));
        byte[] plain = cipher.doFinal(data, 13, data.length - 13);
        try { return new JSONObject(new String(plain, StandardCharsets.UTF_8)); } finally { java.util.Arrays.fill(plain, (byte) 0); }
    }
    private void write(JSONObject entries) throws Exception {
        byte[] plain = entries.toString().getBytes(StandardCharsets.UTF_8);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain);
            if (iv.length != 12) throw new IllegalStateException("Некорректный nonce");
            byte[] out = new byte[13 + encrypted.length]; out[0] = 1;
            System.arraycopy(iv, 0, out, 1, 12); System.arraycopy(encrypted, 0, out, 13, encrypted.length);
            if (!preferences.edit().putString("data", Base64.getEncoder().encodeToString(out)).commit()) throw new IllegalStateException("Не удалось сохранить напоминания");
        } finally { java.util.Arrays.fill(plain, (byte) 0); }
    }
    private String account(String identity) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    }
    void replaceAccount(String identity, Map<String, String> dates) throws Exception {
        synchronized (MUTEX) { JSONObject all = read(), account = new JSONObject();
            for (Map.Entry<String, String> item : dates.entrySet()) { LocalDate.parse(item.getValue()); account.put(item.getKey(), item.getValue()); }
            all.put(account(identity), account); write(all);
        }
    }
    void put(String identity, String id, String date) throws Exception {
        synchronized (MUTEX) { JSONObject all = read(); String accountId = account(identity); JSONObject account = all.optJSONObject(accountId);
            if (account == null) account = new JSONObject();
            if (date.isEmpty()) account.remove(id); else { LocalDate.parse(date); account.put(id, date); }
            all.put(accountId, account); write(all);
        }
    }
    int dueCount(LocalDate today) throws Exception {
        synchronized (MUTEX) { JSONObject all = read(); int count = 0;
            for (Iterator<String> accounts = all.keys(); accounts.hasNext();) { JSONObject entries = all.getJSONObject(accounts.next());
                for (Iterator<String> ids = entries.keys(); ids.hasNext();) { LocalDate date = LocalDate.parse(entries.getString(ids.next()));
                    if (!date.isAfter(today.plusDays(30))) count++;
                }
            }
            return count;
        }
    }
}
