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
                View next = find(root, "Далее"); assertNotNull(next); next.performClick(); assertNotNull(find(root, "Страница 2 из 2"));
                dialog.dismiss();
            });
            runner.waitForIdleSync();
            File[] files = new File(runner.getTargetContext().getCacheDir(), "preview").listFiles();
            assertTrue(files == null || files.length == 0);
        } finally { activity.finish(); }
    }
    private static View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = find(((ViewGroup) root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
}
