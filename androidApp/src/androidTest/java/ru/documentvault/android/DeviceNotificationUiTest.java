package ru.documentvault.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in device test: requires the user to grant notification permission if asked. */
@RunWith(AndroidJUnit4.class)
public final class DeviceNotificationUiTest {
    @Test public void deliversGenericReminderForSyntheticDocument() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("notifications")));
        Context context = runner.getTargetContext();
        SharedPreferences state = context.getSharedPreferences("reminder_state", Context.MODE_PRIVATE);
        String previousDate = state.getString("last_notification", null);
        assumeTrue("Notification already shown today; refusing to disturb real state", !LocalDate.now().toString().equals(previousDate));
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) assumeTrue("Notification ID already in use", notification.getId() != 1001);
        String root = "http://127.0.0.1:17070", username = "notify" + UUID.randomUUID().toString().replace("-", ""), pass = "test-login-password", recovery = "recovery-password-123";
        VaultApi api = new VaultApi(root); api.register(username, pass, recovery.toCharArray()); String token = api.login(username, pass);
        byte[] key = api.unlock(token, recovery.toCharArray()); String id = UUID.randomUUID().toString();
        api.upload(token, id, VaultCrypto.encrypt(key, "test".getBytes(StandardCharsets.UTF_8), "document:" + id));
        byte[] metadata = new JSONObject().put("name", "Тест.pdf").put("text", "").put("expiry", LocalDate.now().plusDays(5).toString()).toString().getBytes(StandardCharsets.UTF_8);
        api.saveMetadata(token, id, VaultCrypto.encrypt(key, metadata, "metadata:" + id));
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = runner.startActivitySync(intent);
        try {
            DeviceUiTest.waitFor(runner, activity, "Войти");
            runner.runOnMainSync(() -> { View view = activity.getWindow().getDecorView();
                ((EditText)DeviceUiTest.findByHint(view, "https://example.com")).setText(root);
                ((EditText)DeviceUiTest.findByHint(view, "Ваш логин")).setText(username);
                ((EditText)DeviceUiTest.findByHint(view, "Пароль входа")).setText(pass);
                DeviceUiTest.findByText(view, "Войти").performClick();
            });
            DeviceUiTest.waitFor(runner, activity, "Разблокировать");
            runner.runOnMainSync(() -> { View view = activity.getWindow().getDecorView();
                ((EditText)DeviceUiTest.findByHint(view, "Ваш пароль восстановления")).setText(recovery);
                DeviceUiTest.findByText(view, "Разблокировать").performClick();
            });
            boolean delivered = false; long deadline = System.currentTimeMillis() + 60000;
            while (System.currentTimeMillis() < deadline && !delivered) {
                for (android.service.notification.StatusBarNotification notice : manager.getActiveNotifications()) if (notice.getId() == 1001) delivered = true;
                if (!delivered) Thread.sleep(300);
            }
            assertTrue("Notification not delivered; check notification permission", delivered);
        } finally {
            activity.finish(); new ReminderStore(context).replaceAccount(root + "\u0000" + username, Collections.emptyMap());
            manager.cancel(1001);
            SharedPreferences.Editor editor = state.edit();
            if (previousDate == null) editor.remove("last_notification"); else editor.putString("last_notification", previousDate);
            editor.commit();
        }
    }
}
