package ru.documentvault.android;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.security.KeyStore;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DeviceSessionStoreTest {
    @Test public void sessionIsEncryptedRestoredAndCleared() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "test-session-" + UUID.randomUUID(), alias = name;
        SessionStore store = new SessionStore(context, name, alias);
        try {
            assertNull(store.load());
            store.save(new SessionStore.Session("https://example.com", "test-user", "test-secret-token"));
            String stored = context.getSharedPreferences(name, Context.MODE_PRIVATE).getString("data", "");
            assertFalse(stored.contains("test-secret-token"));
            SessionStore.Session restored = new SessionStore(context, name, alias).load();
            assertEquals("https://example.com", restored.address); assertEquals("test-user", restored.username); assertEquals("test-secret-token", restored.token);
            store.clear(); assertNull(store.load());
        } finally {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore"); keyStore.load(null); keyStore.deleteEntry(alias);
        }
    }
}
