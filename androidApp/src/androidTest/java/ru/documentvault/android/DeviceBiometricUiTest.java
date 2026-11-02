package ru.documentvault.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import androidx.biometric.BiometricManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assume.assumeTrue;

/** Opt-in test; requires two physical biometric confirmations. */
@RunWith(AndroidJUnit4.class)
public final class DeviceBiometricUiTest {
    @Test public void enrollAndUnlockWithUserConfirmation() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("biometric")));
        assumeTrue(BiometricManager.from(runner.getTargetContext()).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS);
        String root = "http://127.0.0.1:17070", username = "bio" + UUID.randomUUID().toString().replace("-", ""), pass = "test-login-password", recovery = "recovery-password-123";
        VaultApi api = new VaultApi(root); api.register(username, pass, recovery.toCharArray());
        Intent intent = new Intent(runner.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
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
            DeviceUiTest.waitFor(runner, activity, "Настроить биометрию");
            runner.runOnMainSync(() -> DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Настроить биометрию").performClick());
            DeviceUiTest.waitFor(runner, activity, "Биометрия настроена для этого устройства.");
            runner.runOnMainSync(() -> DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Заблокировать").performClick());
            DeviceUiTest.waitFor(runner, activity, "Войти по биометрии");
            runner.runOnMainSync(() -> DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Войти по биометрии").performClick());
            DeviceUiTest.waitFor(runner, activity, "Мои документы");
        } finally { activity.finish(); new BiometricKeyStore(runner.getTargetContext()).forget(root + "\u0000" + username); }
    }
}
