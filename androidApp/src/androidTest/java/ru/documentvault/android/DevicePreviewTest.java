package ru.documentvault.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.pdf.PdfDocument;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DevicePreviewTest {
    @Test public void pdfPagesAndTemporaryFileAreHandled() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        Activity activity = runner.startActivitySync(new Intent(runner.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        PdfDocument pdf = new PdfDocument();
        for (int i = 0; i < 2; i++) { PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(300, 400, i + 1).create()); page.getCanvas().drawColor(android.graphics.Color.WHITE); pdf.finishPage(page); }
        ByteArrayOutputStream output = new ByteArrayOutputStream(); pdf.writeTo(output); pdf.close();
        Class<?> dataClass = Class.forName("ru.documentvault.android.MainActivity$FileData");
        java.lang.reflect.Constructor<?> constructor = dataClass.getDeclaredConstructor(String.class, byte[].class); constructor.setAccessible(true);
        Object data = constructor.newInstance("test.pdf", output.toByteArray());
        java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("showPreview", dataClass); show.setAccessible(true);
        java.lang.reflect.Field dialogField = MainActivity.class.getDeclaredField("previewDialog"); dialogField.setAccessible(true);
        AtomicReference<AlertDialog> ref = new AtomicReference<>();
        try {
            runner.runOnMainSync(() -> { try { show.invoke(activity, data); ref.set((AlertDialog) dialogField.get(activity)); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } });
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                AlertDialog dialog = ref.get(); assertNotNull(dialog); assertTrue(dialog.isShowing());
                View root = dialog.getWindow().getDecorView(); assertNotNull(find(root, "Страница 1 из 2"));
                ZoomImageView zoom = findZoom(root); assertNotNull(zoom);
                find(root, "+").performClick(); assertTrue(zoom.zoomLevel() > 1f);
                find(root, "−").performClick(); assertEquals(1f, zoom.zoomLevel(), 0.01f);
                find(root, "+").performClick();
                View next = find(root, "Далее"); assertNotNull(next); next.performClick(); assertNotNull(find(root, "Страница 2 из 2"));
                assertEquals(1f, zoom.zoomLevel(), 0.01f);
                dialog.dismiss();
            });
            runner.waitForIdleSync();
            File[] files = new File(runner.getTargetContext().getCacheDir(), "preview").listFiles();
            assertTrue(files == null || files.length == 0);
        } finally { activity.finish(); }
    }
    @Test public void imageZoomButtonsAndReset() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        Activity activity = runner.startActivitySync(new Intent(runner.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(500, 700, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.WHITE); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle();
        Class<?> dataClass = Class.forName("ru.documentvault.android.MainActivity$FileData");
        java.lang.reflect.Constructor<?> constructor = dataClass.getDeclaredConstructor(String.class, byte[].class); constructor.setAccessible(true);
        Object data = constructor.newInstance("example.png", bytes.toByteArray());
        java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("showPreview", dataClass); show.setAccessible(true);
        java.lang.reflect.Field dialogField = MainActivity.class.getDeclaredField("previewDialog"); dialogField.setAccessible(true);
        AtomicReference<AlertDialog> ref = new AtomicReference<>();
        try {
            runner.runOnMainSync(() -> { try { show.invoke(activity, data); ref.set((AlertDialog) dialogField.get(activity)); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } });
            runner.waitForIdleSync();
            runner.runOnMainSync(() -> {
                View root = ref.get().getWindow().getDecorView(); ZoomImageView zoom = findZoom(root); assertNotNull(zoom);
                assertEquals(1f, zoom.zoomLevel(), 0.01f); find(root, "+").performClick(); assertTrue(zoom.zoomLevel() > 1f);
                find(root, "По размеру экрана").performClick(); assertEquals(1f, zoom.zoomLevel(), 0.01f);
                pinchOut(zoom); assertTrue("Pinch should enlarge image", zoom.zoomLevel() > 1.05f);
                find(root, "По размеру экрана").performClick(); assertEquals(1f, zoom.zoomLevel(), 0.01f);
                ref.get().dismiss();
            });
        } finally { activity.finish(); }
    }
    @Test public void ocrPreviewTextIsSelectable() throws Exception {
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        Activity activity = runner.startActivitySync(new Intent(runner.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(500, 700, android.graphics.Bitmap.Config.ARGB_8888); image.eraseColor(android.graphics.Color.WHITE);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes); image.recycle();
        Class<?> dataClass = Class.forName("ru.documentvault.android.MainActivity$FileData");
        java.lang.reflect.Constructor<?> constructor = dataClass.getDeclaredConstructor(String.class, byte[].class, String.class, String.class); constructor.setAccessible(true);
        Object data = constructor.newInstance("ocr.png", bytes.toByteArray(), "Пример текста", "{\"version\":1,\"words\":[[\"Пример\",0.1,0.2,0.4,0.3],[\"текста\",0.45,0.2,0.8,0.3]]}");
        java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("showPreview", dataClass); show.setAccessible(true);
        java.lang.reflect.Field dialog = MainActivity.class.getDeclaredField("previewDialog"); dialog.setAccessible(true);
        try {
            runner.runOnMainSync(() -> { try { show.invoke(activity, data); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } });
            runner.waitForIdleSync();
            AtomicReference<ZoomImageView> zoomRef = new AtomicReference<>(); float[] points = {100, 175, 300, 175};
            long down = android.os.SystemClock.uptimeMillis();
            runner.runOnMainSync(() -> { try {
                AlertDialog d = (AlertDialog) dialog.get(activity); ZoomImageView view = findZoom(d.getWindow().getDecorView()); assertNotNull(view); zoomRef.set(view);
                view.getImageMatrix().mapPoints(points);
                android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, points[0], points[1], 0); view.dispatchTouchEvent(event); event.recycle();
            } catch (IllegalAccessException e) { throw new AssertionError(e); } });
            Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 250L);
            runner.runOnMainSync(() -> {
                ZoomImageView view = zoomRef.get();
                android.view.MotionEvent event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_MOVE, points[2], points[3], 0); view.dispatchTouchEvent(event); event.recycle();
                assertEquals("Пример текста", view.selectedText());
                event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_UP, points[2], points[3], 0); view.dispatchTouchEvent(event); event.recycle();
                try { ((AlertDialog) dialog.get(activity)).dismiss(); } catch (IllegalAccessException e) { throw new AssertionError(e); }
            });
        } finally { activity.finish(); }
    }
    private static void pinchOut(ZoomImageView view) {
        long down = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent.PointerProperties first = new android.view.MotionEvent.PointerProperties(), second = new android.view.MotionEvent.PointerProperties();
        first.id = 0; first.toolType = android.view.MotionEvent.TOOL_TYPE_FINGER; second.id = 1; second.toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
        android.view.MotionEvent.PointerCoords a = new android.view.MotionEvent.PointerCoords(), b = new android.view.MotionEvent.PointerCoords();
        a.x = view.getWidth() / 2f - 100; a.y = view.getHeight() / 2f; a.pressure = 1; a.size = 1;
        b.x = view.getWidth() / 2f + 100; b.y = a.y; b.pressure = 1; b.size = 1;
        android.view.MotionEvent.PointerProperties[] properties = {first, second}; android.view.MotionEvent.PointerCoords[] coordinates = {a, b};
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, 1, properties, coordinates, 0, 0, 1, 1, 0, 0, 0, 0); view.dispatchTouchEvent(event); event.recycle();
        event = android.view.MotionEvent.obtain(down, down + 20, android.view.MotionEvent.ACTION_POINTER_DOWN | (1 << android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coordinates, 0, 0, 1, 1, 0, 0, 0, 0); view.dispatchTouchEvent(event); event.recycle();
        for (int step = 1; step <= 8; step++) {
            a.x -= 20; b.x += 20;
            event = android.view.MotionEvent.obtain(down, down + 20 + step * 30, android.view.MotionEvent.ACTION_MOVE, 2, properties, coordinates, 0, 0, 1, 1, 0, 0, 0, 0); view.dispatchTouchEvent(event); event.recycle();
        }
        event = android.view.MotionEvent.obtain(down, down + 400, android.view.MotionEvent.ACTION_UP, 1, properties, coordinates, 0, 0, 1, 1, 0, 0, 0, 0); view.dispatchTouchEvent(event); event.recycle();
    }
    private static ZoomImageView findZoom(View root) {
        if (root instanceof ZoomImageView) return (ZoomImageView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { ZoomImageView found = findZoom(((ViewGroup) root).getChildAt(i)); if (found != null) return found; }
        return null;
    }
    private static View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = find(((ViewGroup) root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
}
