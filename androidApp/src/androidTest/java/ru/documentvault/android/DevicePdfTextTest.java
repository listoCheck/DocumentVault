package ru.documentvault.android;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DevicePdfTextTest {
    @Test public void viewerLoadsTheSelectableTextLayer() throws Exception {
        android.app.Instrumentation runner = InstrumentationRegistry.getInstrumentation(); PdfTextLayer.init(runner.getTargetContext());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 24); stream.newLineAtOffset(60, 700); stream.showText("FIRST DOCUMENT"); stream.endText();
            }
            document.save(bytes);
        }
        android.app.Activity activity = runner.startActivitySync(new android.content.Intent(runner.getTargetContext(), MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Class<?> dataClass = Class.forName("ru.documentvault.android.MainActivity$FileData"); java.lang.reflect.Constructor<?> constructor = dataClass.getDeclaredConstructor(String.class, byte[].class); constructor.setAccessible(true);
        Object data = constructor.newInstance("test.pdf", bytes.toByteArray());
        java.lang.reflect.Method show = MainActivity.class.getDeclaredMethod("showPreview", dataClass); show.setAccessible(true);
        java.lang.reflect.Field dialogField = MainActivity.class.getDeclaredField("previewDialog"); dialogField.setAccessible(true);
        java.lang.reflect.Field wordsField = ZoomImageView.class.getDeclaredField("words"); wordsField.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<ZoomImageView> view = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            runner.runOnMainSync(() -> { try {
                show.invoke(activity, data); android.app.AlertDialog dialog = (android.app.AlertDialog) dialogField.get(activity);
                view.set(findImage(dialog.getWindow().getDecorView()));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); } });
            assertNotNull(view.get()); long deadline = System.currentTimeMillis() + 15000; boolean loaded = false;
            while (!loaded && System.currentTimeMillis() < deadline) {
                java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
                runner.runOnMainSync(() -> { try { found.set(((java.util.List<?>) wordsField.get(view.get())).size() == 2); } catch (IllegalAccessException e) { throw new AssertionError(e); } });
                loaded = found.get(); if (!loaded) Thread.sleep(100);
            }
            assertTrue("Embedded text should be loaded into the image viewer", loaded);
        } finally { runner.runOnMainSync(() -> { try { ((android.app.AlertDialog) dialogField.get(activity)).dismiss(); } catch (IllegalAccessException e) { throw new AssertionError(e); } }); activity.finish(); }
    }
    private static ZoomImageView findImage(android.view.View node) {
        if (node instanceof ZoomImageView) return (ZoomImageView) node;
        if (node instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) node).getChildCount(); i++) {
            ZoomImageView image = findImage(((android.view.ViewGroup) node).getChildAt(i)); if (image != null) return image;
        }
        return null;
    }
    @Test public void embeddedTextHasSelectableCoordinatesAndNeedsNoOcr() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext(); PdfTextLayer.init(context);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (String text : new String[]{"FIRST DOCUMENT", "SECOND PAGE"}) {
                PDPage page = new PDPage(); document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 24); stream.newLineAtOffset(60, 700); stream.showText(text); stream.endText();
                }
            }
            JSONArray words = PdfTextLayer.page(document, 0).getJSONArray("words");
            assertEquals("FIRST", words.getJSONArray(0).getString(0)); assertEquals("DOCUMENT", words.getJSONArray(1).getString(0));
            assertTrue(words.getJSONArray(0).getDouble(1) > 0 && words.getJSONArray(0).getDouble(3) < 1);
            assertEquals("SECOND PAGE", PdfTextLayer.text(document, 1)); document.save(bytes);
        }
        String text = LocalOcr.recognizePdf(context, bytes.toByteArray());
        assertTrue(text.contains("FIRST DOCUMENT")); assertTrue(text.contains("SECOND PAGE"));
    }
}
