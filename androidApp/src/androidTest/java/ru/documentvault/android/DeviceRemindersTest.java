package ru.documentvault.android;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.biometric.BiometricManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.time.LocalDate;
import java.util.Collections;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class DeviceRemindersTest {
    @Test public void scheduleIsEncryptedAndRestored() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ReminderStore store = new ReminderStore(context);
        String identity = "synthetic-test-account", id = "synthetic-document-id", date = LocalDate.now().plusDays(5).toString();
        try {
            store.replaceAccount(identity, Collections.singletonMap(id, date));
            assertTrue(store.dueCount(LocalDate.now()) >= 1);
            String stored = context.getSharedPreferences("reminders", Context.MODE_PRIVATE).getString("data", "");
            assertFalse(stored.contains(date)); assertFalse(stored.contains(id));
        } finally { store.replaceAccount(identity, Collections.emptyMap()); }
    }
    @Test public void keystoreCanPrepareBiometricCipherWhenStrongBiometricsAreEnrolled() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS);
        BiometricKeyStore store = new BiometricKeyStore(context); String alias = store.createAlias();
        try { assertNotNull(store.encryptionCipher(alias)); } finally { store.removeAlias(alias); }
    }
}
