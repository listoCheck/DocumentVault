package ru.documentvault.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DeviceServerSelectionTest {
    @Test public void defaultAndCustomServerSurviveScreenChanges() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        SharedPreferences prefs = runner.getTargetContext().getSharedPreferences("MainActivity", 0);
        boolean hadValue = prefs.contains("server_address"); String previous = prefs.getString("server_address", null);
        Activity activity = null;
        try {
            prefs.edit().remove("server_address").commit();
            activity = runner.startActivitySync(new Intent(runner.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            Activity screen = activity;
            runner.runOnMainSync(() -> {
                EditText address = (EditText) DeviceUiTest.findByHint(screen.getWindow().getDecorView(), "https://example.com");
                assertNotNull(address); assertEquals("https://95.174.95.143", address.getText().toString());
                address.setText("https://vault.example.org");
                DeviceUiTest.findByText(screen.getWindow().getDecorView(), "Создать аккаунт").performClick();
                EditText registration = (EditText) DeviceUiTest.findByHint(screen.getWindow().getDecorView(), "https://example.com");
                assertEquals("https://vault.example.org", registration.getText().toString());
                registration.setText("https://other.example.org");
                DeviceUiTest.findByText(screen.getWindow().getDecorView(), "Уже есть аккаунт").performClick();
                assertEquals("https://other.example.org", address.getText().toString());
            });
            java.lang.reflect.Method remember = MainActivity.class.getDeclaredMethod("rememberServer", String.class); remember.setAccessible(true);
            remember.invoke(activity, "https://other.example.org");
            assertEquals("https://other.example.org", prefs.getString("server_address", null));
        } finally {
            if (activity != null) activity.finish();
            SharedPreferences.Editor editor = prefs.edit(); if (hadValue) editor.putString("server_address", previous); else editor.remove("server_address"); editor.commit();
        }
    }
}
