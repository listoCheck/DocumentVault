package ru.documentvault.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public final class DeviceFoldersTest {
    @Test public void folderFilteringAndPreviewUseEncryptedMetadata() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("network")));
        String root = "http://127.0.0.1:17070", user = "folder" + UUID.randomUUID().toString().replace("-", "");
        String password = "test-login-password", recovery = "recovery-password-123";
        VaultApi api = new VaultApi(root); api.register(user, password, recovery.toCharArray()); String token = api.login(user, password);
        byte[] key = api.unlock(token, recovery.toCharArray()); String id = UUID.randomUUID().toString();
        Bitmap bitmap = Bitmap.createBitmap(1400, 350, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.WHITE);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap); android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG); paint.setColor(android.graphics.Color.BLACK); paint.setTextSize(100); canvas.drawText("DOCUMENT 2030", 40, 180, paint);
        ByteArrayOutputStream stream = new ByteArrayOutputStream(); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); bitmap.recycle();
        byte[] name = "Пример.png".getBytes(StandardCharsets.UTF_8), content = stream.toByteArray();
        byte[] payload = ByteBuffer.allocate(4 + name.length + content.length).putInt(name.length).put(name).put(content).array();
        api.upload(token, id, VaultCrypto.encrypt(key, payload, "document:" + id));
        LocalOcr.ImageResult recognized = LocalOcr.recognizeImage(runner.getTargetContext(), content);
        assertTrue(recognized.layout.getJSONArray("words").length() > 0);
        byte[] metadata = new JSONObject().put("name", "Пример.png").put("text", "тестовый договор").put("expiry", "").put("folder", "Работа").put("ocrLayout", recognized.layout).toString().getBytes(StandardCharsets.UTF_8);
        api.saveMetadata(token, id, VaultCrypto.encrypt(key, metadata, "metadata:" + id));
        Activity activity = runner.startActivitySync(new Intent(runner.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            DeviceUiTest.waitFor(runner, activity, "Войти");
            runner.runOnMainSync(() -> {
                ((EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "https://example.com")).setText(root);
                ((EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "Ваш логин")).setText(user);
                ((EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "Пароль входа")).setText(password);
                DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Войти").performClick();
            });
            DeviceUiTest.waitFor(runner, activity, "Разблокировать");
            runner.runOnMainSync(() -> {
                ((EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "Ваш пароль восстановления")).setText(recovery);
                DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Разблокировать").performClick();
            });
            DeviceUiTest.waitFor(runner, activity, "Пример.png");
            runner.runOnMainSync(() -> {
                assertNotNull(DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Работа"));
                DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Без папки").performClick();
                assertNull(DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Пример.png"));
                EditText search = (EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "Поиск по документам");
                search.setText("договор"); assertNotNull(DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Пример.png")); search.setText("");
                DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Работа").performClick();
                assertNotNull(DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Пример.png"));
                ((EditText) DeviceUiTest.findByHint(activity.getWindow().getDecorView(), "Поиск по документам")).setText("договор");
                assertNotNull(DeviceUiTest.findByText(activity.getWindow().getDecorView(), "Пример.png"));
                try {
                    java.lang.reflect.Method actions = MainActivity.class.getDeclaredMethod("showDocumentActions", String.class, String.class); actions.setAccessible(true);
                    AlertDialog dialog = (AlertDialog) actions.invoke(activity, id, "Пример.png");
                    assertNotNull(DeviceUiTest.findByText(dialog.getWindow().getDecorView(), "Переименовать")); dialog.dismiss();
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                try { java.lang.reflect.Method preview = MainActivity.class.getDeclaredMethod("preview", String.class); preview.setAccessible(true); preview.invoke(activity, id); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("previewDialog"); field.setAccessible(true);
            boolean opened = false; long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline && !opened) {
                AtomicReference<Boolean> shown = new AtomicReference<>(false);
                runner.runOnMainSync(() -> { try { AlertDialog dialog = (AlertDialog) field.get(activity); shown.set(dialog != null && dialog.isShowing()); } catch (IllegalAccessException e) { throw new AssertionError(e); } });
                opened = shown.get(); if (!opened) Thread.sleep(100);
            }
            assertTrue("Image preview must open", opened);
            runner.runOnMainSync(() -> {
                try {
                    ((AlertDialog) field.get(activity)).dismiss();
                    java.lang.reflect.Method overlay = MainActivity.class.getDeclaredMethod("previewWithOcr", String.class); overlay.setAccessible(true); overlay.invoke(activity, id);
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            opened = false; deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline && !opened) {
                AtomicReference<Boolean> shown = new AtomicReference<>(false);
                runner.runOnMainSync(() -> { try { AlertDialog dialog = (AlertDialog) field.get(activity); shown.set(dialog != null && dialog.isShowing()); } catch (IllegalAccessException e) { throw new AssertionError(e); } });
                opened = shown.get(); if (!opened) Thread.sleep(100);
            }
            assertTrue("OCR overlay must open", opened);
            runner.runOnMainSync(() -> {
                try {
                    ((AlertDialog) field.get(activity)).dismiss();
                    java.lang.reflect.Method move = MainActivity.class.getDeclaredMethod("saveFolder", String.class, String.class); move.setAccessible(true); move.invoke(activity, id, "Личное");
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            JSONObject stored = null; deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                byte[] encrypted = api.metadata(token, id);
                byte[] plain = VaultCrypto.decrypt(key, encrypted, "metadata:" + id);
                try { stored = new JSONObject(new String(plain, StandardCharsets.UTF_8)); } finally { java.util.Arrays.fill(plain, (byte) 0); }
                if ("Личное".equals(stored.optString("folder"))) break;
                Thread.sleep(100);
            }
            assertNotNull(stored); assertEquals("Личное", stored.getString("folder")); assertEquals("тестовый договор", stored.getString("text"));
            DeviceUiTest.waitFor(runner, activity, "Личное");
            runner.runOnMainSync(() -> { try { java.lang.reflect.Method rename = MainActivity.class.getDeclaredMethod("saveName", String.class, String.class); rename.setAccessible(true); rename.invoke(activity, id, "Новый.png"); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } });
            deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                byte[] plain = VaultCrypto.decrypt(key, api.metadata(token, id), "metadata:" + id);
                try { stored = new JSONObject(new String(plain, StandardCharsets.UTF_8)); } finally { java.util.Arrays.fill(plain, (byte) 0); }
                if ("Новый.png".equals(stored.optString("name"))) break;
                Thread.sleep(100);
            }
            assertEquals("Новый.png", stored.getString("name")); assertEquals("Личное", stored.getString("folder")); assertEquals("тестовый договор", stored.getString("text"));
            DeviceUiTest.waitFor(runner, activity, "Новый.png");
            Class<?> uploadClass = Class.forName("ru.documentvault.android.MainActivity$UploadResult");
            java.lang.reflect.Constructor<?> upload = uploadClass.getDeclaredConstructor(String.class, String.class, String.class, String.class); upload.setAccessible(true);
            Object corrected = upload.newInstance(id, "Новый.png", "исправленный договор", "");
            runner.runOnMainSync(() -> { try { java.lang.reflect.Method save = MainActivity.class.getDeclaredMethod("saveOcr", uploadClass, String.class); save.setAccessible(true); save.invoke(activity, corrected, ""); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } });
            deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline) {
                byte[] plain = VaultCrypto.decrypt(key, api.metadata(token, id), "metadata:" + id);
                try { stored = new JSONObject(new String(plain, StandardCharsets.UTF_8)); } finally { java.util.Arrays.fill(plain, (byte) 0); }
                if ("исправленный договор".equals(stored.optString("text"))) break;
                Thread.sleep(100);
            }
            assertEquals("исправленный договор", stored.getString("text")); assertEquals("Личное", stored.getString("folder")); assertEquals("Новый.png", stored.getString("name")); assertFalse(stored.has("ocrLayout"));
        } finally { activity.finish(); java.util.Arrays.fill(key, (byte) 0); java.util.Arrays.fill(payload, (byte) 0); }
    }
}
