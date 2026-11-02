package ru.documentvault.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Wraps each account's master key using a non-exportable, per-use biometric Keystore key. */
final class BiometricKeyStore {
    private final SharedPreferences preferences;
    BiometricKeyStore(Context context) { preferences = context.getSharedPreferences("biometric_keys", Context.MODE_PRIVATE); }
    private String accountKey(String identity) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
    boolean hasKey(String identity) {
        try { String key = accountKey(identity); return preferences.contains(key + ".alias") && preferences.contains(key + ".data"); }
        catch (Exception e) { return false; }
    }
    String createAlias() throws Exception {
        String alias = "documentvault." + UUID.randomUUID();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        else builder.setUserAuthenticationValidityDurationSeconds(-1);
        generator.init(builder.build()); generator.generateKey(); return alias;
    }
    private SecretKey key(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey key = (SecretKey) store.getKey(alias, null);
        if (key == null) throw new IllegalStateException("Ключ устройства недоступен. Используйте пароль восстановления.");
        return key;
    }
    Cipher encryptionCipher(String alias) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(alias)); return cipher;
    }
    Cipher decryptionCipher(String identity) throws Exception {
        String account = accountKey(identity), alias = preferences.getString(account + ".alias", null), encoded = preferences.getString(account + ".data", null);
        if (alias == null || encoded == null) throw new IllegalStateException("Биометрия не настроена для этого аккаунта.");
        byte[] data = Base64.getDecoder().decode(encoded);
        if (data.length < 29 || data[0] != 1) throw new IllegalStateException("Повреждён ключ устройства.");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(alias), new GCMParameterSpec(128, java.util.Arrays.copyOfRange(data, 1, 13)));
        return cipher;
    }
    byte[] encryptedKey(String identity) throws Exception {
        String value = preferences.getString(accountKey(identity) + ".data", null);
        if (value == null) throw new IllegalStateException("Ключ устройства отсутствует.");
        byte[] data = Base64.getDecoder().decode(value);
        if (data.length < 29 || data[0] != 1) throw new IllegalStateException("Повреждён ключ устройства.");
        return java.util.Arrays.copyOfRange(data, 13, data.length);
    }
    void store(String identity, String alias, byte[] iv, byte[] ciphertext) throws Exception {
        if (iv.length != 12) throw new IllegalArgumentException("Некорректный nonce");
        String account = accountKey(identity), old = preferences.getString(account + ".alias", null);
        byte[] envelope = new byte[1 + iv.length + ciphertext.length]; envelope[0] = 1;
        System.arraycopy(iv, 0, envelope, 1, iv.length); System.arraycopy(ciphertext, 0, envelope, 13, ciphertext.length);
        if (!preferences.edit().putString(account + ".alias", alias).putString(account + ".data", Base64.getEncoder().encodeToString(envelope)).commit()) throw new IllegalStateException("Не удалось сохранить ключ устройства.");
        if (old != null && !old.equals(alias)) try { removeAlias(old); } catch (Exception ignored) { }
    }
    void removeAlias(String alias) throws Exception { KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(alias); }
    void forget(String identity) throws Exception {
        String account = accountKey(identity), alias = preferences.getString(account + ".alias", null);
        if (!preferences.edit().remove(account + ".alias").remove(account + ".data").commit()) throw new IllegalStateException("Не удалось удалить привязку");
        if (alias != null) removeAlias(alias);
    }
}
