package ru.documentvault.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class DeviceDocumentScanTest {
    @Test public void detectsPerspectiveAndKeepsOriginal() throws Exception {
        Bitmap image = Bitmap.createBitmap(1200, 1000, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(image); canvas.drawColor(Color.DKGRAY);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.WHITE); Path page = new Path();
        page.moveTo(230, 100); page.lineTo(990, 180); page.lineTo(1030, 850); page.lineTo(160, 900); page.close(); canvas.drawPath(page, paint);
        int original = image.getPixel(0, 0); DocumentScan.Result scan = DocumentScan.scan(image, null);
        try {
            assertNotNull("Page should be detected", scan.corners); assertTrue(scan.image.getWidth() < image.getWidth()); assertTrue(scan.image.getHeight() < image.getHeight());
            assertEquals(original, image.getPixel(0, 0)); assertTrue(Color.red(scan.image.getPixel(scan.image.getWidth() / 2, scan.image.getHeight() / 2)) > 200);
            DocumentScan.Result restored = DocumentScan.scan(image, scan.corners);
            try { assertEquals(scan.image.getWidth(), restored.image.getWidth()); assertEquals(scan.image.getHeight(), restored.image.getHeight()); } finally { restored.image.recycle(); }
        } finally { scan.image.recycle(); image.recycle(); }
    }
    @Test public void detectsWordCoordinatesAndRendersOverlay() throws Exception {
        Bitmap image = Bitmap.createBitmap(1400, 350, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(image); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(100); paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); canvas.drawText("DOCUMENT 2030", 40, 180, paint);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        LocalOcr.ImageResult result = LocalOcr.recognizeImage(InstrumentationRegistry.getInstrumentation().getTargetContext(), output.toByteArray());
        assertTrue(result.text.toUpperCase(java.util.Locale.ROOT).contains("DOCUMENT"));
        JSONArray words = result.layout.getJSONArray("words"); assertTrue("OCR must return word positions", words.length() > 0);
        Bitmap rendered = OcrOverlay.render(image, result.layout);
        try { assertEquals(image.getWidth(), rendered.getWidth()); assertEquals(image.getHeight(), rendered.getHeight()); }
        finally { rendered.recycle(); image.recycle(); }
    }
    @Test public void invalidCoordinatesDoNotCrashOverlay() throws Exception {
        Bitmap source = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
        JSONObject layout = new JSONObject().put("version", 1).put("words", new JSONArray().put(new JSONArray().put("bad").put(-1).put(0).put(10).put(1)));
        Bitmap rendered = OcrOverlay.render(source, layout); rendered.recycle(); source.recycle();
    }
    @Test public void correctedWordsRemainAlignedOnlyWhenWordCountMatches() throws Exception {
        JSONObject layout = new JSONObject().put("version", 1).put("words", new JSONArray()
            .put(new JSONArray().put("DOCUMEN7").put(0.1).put(0.1).put(0.4).put(0.2))
            .put(new JSONArray().put("2030").put(0.5).put(0.1).put(0.7).put(0.2)));
        JSONObject corrected = new JSONObject(OcrOverlay.correctedLayout(layout.toString(), "DOCUMENT 2030"));
        assertEquals("DOCUMENT", corrected.getJSONArray("words").getJSONArray(0).getString(0));
        assertNull(OcrOverlay.correctedLayout(layout.toString(), "DOCUMENT"));
    }
    @Test public void overlayUsesOpaqueGrayAndKeepsTextInsideItsBox() throws Exception {
        Bitmap source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888); source.eraseColor(Color.RED);
        JSONObject layout = new JSONObject().put("version", 1).put("words", new JSONArray()
            .put(new JSONArray().put("").put(0.1).put(0.1).put(0.4).put(0.4))
            .put(new JSONArray().put("DOCUMENT").put(0.5).put(0.5).put(0.9).put(0.9)));
        Bitmap rendered = OcrOverlay.render(source, layout);
        try {
            assertEquals(Color.rgb(224, 226, 225), rendered.getPixel(30, 20));
            assertEquals(Color.RED, rendered.getPixel(2, 2));
            boolean dark = false;
            for (int y = 50; y < 90 && !dark; y++) for (int x = 100; x < 180; x++) if (Color.red(rendered.getPixel(x, y)) < 40) { dark = true; break; }
            assertTrue("Fitted OCR text should be visible", dark);
            assertEquals(Color.RED, rendered.getPixel(181, 91));
        } finally { rendered.recycle(); source.recycle(); }
    }
}
