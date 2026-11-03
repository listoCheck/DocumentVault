package ru.documentvault.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class DeviceHttpsTest {
    @Test public void serverCertificateAndHealthAreValid() throws Exception {
        String root = InstrumentationRegistry.getArguments().getString("https_server");
        assumeTrue(root != null && root.startsWith("https://"));
        HttpsURLConnection connection = (HttpsURLConnection) new URL(root + "/health").openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(10000); connection.setInstanceFollowRedirects(false);
        try {
            assertEquals(200, connection.getResponseCode());
            byte[] bytes = new byte[16]; int count;
            try (java.io.InputStream stream = connection.getInputStream()) { count = stream.read(bytes); }
            assertTrue(count > 0);
            assertEquals("ok", new String(bytes, 0, count, StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }
}
