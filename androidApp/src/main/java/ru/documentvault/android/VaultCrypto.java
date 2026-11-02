package ru.documentvault.android;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Wire-compatible with the desktop v1 envelope. */
public final class VaultCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private VaultCrypto() { }

    public static byte[] derive(char[] password, byte[] salt) throws Exception {
        if (password.length == 0 || salt.length != 16) throw new IllegalArgumentException("Некорректный пароль или соль");
        PBEKeySpec spec = new PBEKeySpec(password, salt, 600_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    public static byte[] encrypt(byte[] key, byte[] plain, String context) throws Exception {
        if (key.length != 32) throw new IllegalArgumentException("Некорректный ключ");
        byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(plain);
        byte[] envelope = new byte[1 + nonce.length + ciphertext.length];
        envelope[0] = 1;
        System.arraycopy(nonce, 0, envelope, 1, nonce.length);
        System.arraycopy(ciphertext, 0, envelope, 13, ciphertext.length);
        return envelope;
    }

    public static byte[] decrypt(byte[] key, byte[] envelope, String context) throws Exception {
        if (key.length != 32 || envelope.length < 29 || envelope[0] != 1) throw new IllegalArgumentException("Некорректные зашифрованные данные");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 1, 13)));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(envelope, 13, envelope.length - 13);
    }
}
