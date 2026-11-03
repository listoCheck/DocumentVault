package ru.documentvault.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in test: the user takes a synthetic, non-sensitive picture in the system camera. */
@RunWith(AndroidJUnit4.class)
public final class DeviceCameraTest {
    @Test public void capturesAndEncryptsSyntheticPhoto() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("camera")));
        String root = "http://127.0.0.1:17070", user = "cam" + UUID.randomUUID().toString().replace("-", "");
        String password = "test-login-password", recovery = "test-recovery-password";
        VaultApi api = new VaultApi(root); api.register(user, password, recovery.toCharArray());
        String token = api.login(user, password); byte[] key = api.unlock(token, recovery.toCharArray());
        Activity activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            DeviceUiTest.waitFor(instrumentation, activity, "Войти");
            instrumentation.runOnMainSync(() -> {
                ((EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "https://example.com")).setText(root);
                ((EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "Ваш логин")).setText(user);
                ((EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "Пароль входа")).setText(password);
                DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Войти").performClick();
            });
            DeviceUiTest.waitFor(instrumentation, activity, "Разблокировать");
            instrumentation.runOnMainSync(() -> {
                ((EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "Ваш пароль восстановления")).setText(recovery);
                DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Разблокировать").performClick();
            });
            DeviceUiTest.waitFor(instrumentation, activity, "Камера");
            instrumentation.runOnMainSync(() -> DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Камера").performClick());
            long until = System.currentTimeMillis() + 120_000;
            String id = null;
            while (System.currentTimeMillis() < until) {
                JSONArray documents = api.list(token);
                if (documents.length() > 0) { id = documents.getJSONObject(0).getString("id"); break; }
                Thread.sleep(500);
            }
            assertNotNull("No photo was uploaded within 120 seconds", id);
            byte[] data = VaultCrypto.decrypt(key, api.download(token, id), "document:" + id);
            try {
                ByteBuffer payload = ByteBuffer.wrap(data); int nameSize = payload.getInt();
                assertTrue(nameSize > 0 && nameSize <= payload.remaining() - 2);
                payload.position(payload.position() + nameSize);
                assertEquals(0xff, payload.get() & 0xff); assertEquals(0xd8, payload.get() & 0xff);
            } finally { Arrays.fill(data, (byte) 0); }
            java.io.File directory = new java.io.File(instrumentation.getTargetContext().getCacheDir(), "camera");
            long cleanupDeadline = System.currentTimeMillis() + 60_000;
            java.io.File[] remaining = directory.listFiles();
            while (remaining != null && remaining.length != 0 && System.currentTimeMillis() < cleanupDeadline) {
                Thread.sleep(500); remaining = directory.listFiles();
            }
            assertTrue("Temporary photo was not removed", remaining == null || remaining.length == 0);
        } finally { Arrays.fill(key, (byte) 0); activity.finish(); }
    }
}
