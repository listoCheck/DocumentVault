package ru.documentvault.android;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import static org.junit.Assert.*;

public class VaultCryptoTest {
    @Test public void recoveryDerivationMatchesIndependentVector() throws Exception {
        byte[] salt = new byte[16]; for (int i = 0; i < salt.length; i++) salt[i] = (byte) i;
        byte[] key = VaultCrypto.derive("recovery-password".toCharArray(), salt);
        assertEquals("0856268bb31bb62b7e28d5aeadf43c987b5f33d881ea695425f002986222424f", hex(key));
    }
    @Test public void envelopeAuthenticatesContextAndCiphertext() throws Exception {
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 42);
        byte[] plain = "документ".getBytes(StandardCharsets.UTF_8);
        byte[] first = VaultCrypto.encrypt(key, plain, "document:123");
        byte[] second = VaultCrypto.encrypt(key, plain, "document:123");
        assertFalse(Arrays.equals(first, second));
        assertArrayEquals(plain, VaultCrypto.decrypt(key, first, "document:123"));
        assertThrows(AEADBadTagException.class, () -> VaultCrypto.decrypt(key, first, "document:other"));
        first[first.length - 1] ^= 1;
        assertThrows(AEADBadTagException.class, () -> VaultCrypto.decrypt(key, first, "document:123"));
    }
    @Test public void decryptsEnvelopeCreatedByDesktopCore() throws Exception {
        byte[] key = new byte[32]; Arrays.fill(key, (byte) 42);
        byte[] envelope = java.util.Base64.getDecoder().decode("AaVAmhJvOKSpoohjPCm2OtuyWwqZysYCdy9v2Po6Z2mSiG132SkqZ6UqssAN");
        assertEquals("interoperability", new String(VaultCrypto.decrypt(key, envelope, "document:123"), StandardCharsets.UTF_8));
    }
    private static String hex(byte[] data) { StringBuilder out = new StringBuilder(); for (byte b : data) out.append(String.format("%02x", b & 255)); return out.toString(); }
}
