package ru.documentvault.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class DeviceNetworkTest {
    @Test public void exchangesEncryptedDataWithUsbServer() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("network")));
        String root = "http://127.0.0.1:17070";
        byte[] key = new byte[32], salt = new byte[16]; new SecureRandom().nextBytes(key); new SecureRandom().nextBytes(salt);
        char[] recovery = "recovery-password-123".toCharArray();
        byte[] wrappingKey = VaultCrypto.derive(recovery, salt);
        String user = "test" + UUID.randomUUID().toString().replace("-", "");
        String password = "test-login-password";
        JSONObject request = new JSONObject().put("username", user).put("password", password)
            .put("recoverySalt", Base64.getEncoder().encodeToString(salt))
            .put("wrappedKey", Base64.getEncoder().encodeToString(VaultCrypto.encrypt(wrappingKey, key, "master-key")));
        HttpURLConnection conn = (HttpURLConnection) new URL(root + "/v1/accounts").openConnection();
        conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json"); conn.setDoOutput(true);
        try { conn.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8)); assertEquals(201, conn.getResponseCode()); } finally { conn.disconnect(); }
        VaultApi api = new VaultApi(root); String token = api.login(user, password);
        byte[] recovered = api.unlock(token, recovery);
        assertArrayEquals(key, recovered);
        String id = UUID.randomUUID().toString(); byte[] payload = "synthetic test content".getBytes(StandardCharsets.UTF_8);
        api.upload(token, id, VaultCrypto.encrypt(recovered, payload, "document:" + id));
        assertArrayEquals(payload, VaultCrypto.decrypt(recovered, api.download(token, id), "document:" + id));
        assertTrue(api.list(token).toString().contains(id));
    }
}
