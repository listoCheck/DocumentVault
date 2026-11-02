package ru.documentvault.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
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
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class DeviceUiTest {
    @Test public void loginUnlockAndDisplayEncryptedDocumentName() throws Exception {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("network")));
        String root = "http://127.0.0.1:17070", username = "ui" + UUID.randomUUID().toString().replace("-", ""), pass = "test-login-password", recovery = "recovery-password-123";
        byte[] key = new byte[32], salt = new byte[16]; new SecureRandom().nextBytes(key); new SecureRandom().nextBytes(salt);
        byte[] wrapped = VaultCrypto.encrypt(VaultCrypto.derive(recovery.toCharArray(), salt), key, "master-key");
        byte[] body = new JSONObject().put("username", username).put("password", pass).put("recoverySalt", Base64.getEncoder().encodeToString(salt)).put("wrappedKey", Base64.getEncoder().encodeToString(wrapped)).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(root + "/v1/accounts").openConnection();
        conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json"); conn.setDoOutput(true);
        try { conn.getOutputStream().write(body); assertEquals(201, conn.getResponseCode()); } finally { conn.disconnect(); }
        VaultApi api = new VaultApi(root); String token = api.login(username, pass); String id = UUID.randomUUID().toString();
        api.upload(token, id, VaultCrypto.encrypt(key, "synthetic".getBytes(StandardCharsets.UTF_8), "document:" + id));
        byte[] metadata = new JSONObject().put("name", "Пример документа.pdf").put("text", "").put("expiry", "").toString().getBytes(StandardCharsets.UTF_8);
        api.saveMetadata(token, id, VaultCrypto.encrypt(key, metadata, "metadata:" + id));

        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        try {
            waitFor(instrumentation, activity, "Войти");
            instrumentation.runOnMainSync(() -> {
                ((EditText)findByHint(activity.getWindow().getDecorView(), "https://example.com")).setText(root);
                ((EditText)findByHint(activity.getWindow().getDecorView(), "Ваш логин")).setText(username);
                ((EditText)findByHint(activity.getWindow().getDecorView(), "Пароль входа")).setText(pass);
                findByText(activity.getWindow().getDecorView(), "Войти").performClick();
            });
            waitFor(instrumentation, activity, "Разблокировать");
            instrumentation.runOnMainSync(() -> {
                ((EditText)findByHint(activity.getWindow().getDecorView(), "Ваш пароль восстановления")).setText(recovery);
                findByText(activity.getWindow().getDecorView(), "Разблокировать").performClick();
            });
            waitFor(instrumentation, activity, "Пример документа.pdf");
            instrumentation.runOnMainSync(() -> {
                assertNotNull(findByText(activity.getWindow().getDecorView(), "Мои документы"));
                assertNotNull(findByText(activity.getWindow().getDecorView(), "Камера"));
                findByText(activity.getWindow().getDecorView(), "Заблокировать").performClick();
                assertNull(findByText(activity.getWindow().getDecorView(), "Пример документа.pdf"));
            });
        } finally { activity.finish(); }
    }
    private static void waitFor(Instrumentation instrumentation, Activity activity, String text) throws Exception {
        long until = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < until) {
            AtomicReference<View> found = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> found.set(findByText(activity.getWindow().getDecorView(), text)));
            if (found.get() != null) return;
            Thread.sleep(100);
        }
        fail("View not found: " + text);
    }
    private static View findByText(View root, String text) {
        if (root instanceof TextView && root.isShown() && text.contentEquals(((TextView)root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { View found = findByText(((ViewGroup)root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private static View findByHint(View root, String hint) {
        if (root instanceof EditText && root.isShown() && hint.contentEquals(((EditText)root).getHint())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)root).getChildCount(); i++) { View found = findByHint(((ViewGroup)root).getChildAt(i), hint); if (found != null) return found; }
        return null;
    }
}
