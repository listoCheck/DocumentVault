package ru.documentvault.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.Matrix;
import android.media.ExifInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Locale;

/** Synthetic input only: no personal documents are read or transmitted. */
@RunWith(AndroidJUnit4.class)
public final class DeviceOcrTest {
    @Test public void skipsBlueStampTextButKeepsBlackDocumentText() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); paint.setTextSize(72);
        paint.setColor(Color.BLACK); canvas.drawText("DOCUMENT 2030", 60, 180, paint); paint.setColor(Color.rgb(40, 65, 150)); canvas.drawText("FAKESTAMP", 60, 430, paint);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)); bitmap.recycle();
        String text = LocalOcr.recognizeImage(InstrumentationRegistry.getInstrumentation().getTargetContext(), bytes.toByteArray(), false).text.toUpperCase(Locale.ROOT);
        assertTrue(text, text.contains("DOCUMENT")); assertTrue(text, !text.contains("FAKESTAMP"));
    }
    @Test public void recognizesSmallLinesOnUnevenDimPage() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(900, 1250, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 900, 1250, Color.rgb(125,125,125), Color.rgb(205,205,205), android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 900, 1250, paint); paint.setShader(null); paint.setColor(Color.rgb(20,20,20)); paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextSize(27);
        canvas.drawText("СПРАВКА", 85, 130, paint); canvas.drawText("ДОКУМЕНТ ДЕЙСТВУЕТ ДО 31.12.2030", 85, 210, paint);
        canvas.drawText("ПРОВЕРКА ТЕКСТА И ДАННЫХ", 85, 275, paint); canvas.drawText("НОМЕР ДОКУМЕНТА 123456", 85, 340, paint); canvas.drawText("ДАТА 15.10.2025", 85, 405, paint);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)); bitmap.recycle();
        String result = LocalOcr.recognizeImage(InstrumentationRegistry.getInstrumentation().getTargetContext(), bytes.toByteArray(), false).text.toUpperCase(Locale.ROOT);
        for (String expected : new String[]{"СПРАВКА", "ДОКУМЕНТ", "ДЕЙСТВУЕТ", "ПРОВЕРКА", "ТЕКСТА", "НОМЕР", "ДАТА"}) assertTrue("Missing " + expected + ": " + result, result.contains(expected));
    }
    @Test public void testDesktopEncryptionEnvelopeOnDevice() throws Exception {
        byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 42);
        byte[] envelope = java.util.Base64.getDecoder().decode("AaVAmhJvOKSpoohjPCm2OtuyWwqZysYCdy9v2Po6Z2mSiG132SkqZ6UqssAN");
        assertEquals("interoperability", new String(VaultCrypto.decrypt(key, envelope, "document:123"), java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void testRecognizesSyntheticRussianAndEnglish() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1600, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); paint.setTextSize(100);
        canvas.drawText("DOCUMENT 2030", 30, 130, paint); canvas.drawText("ДОКУМЕНТ", 30, 280, paint);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)); bitmap.recycle();
        String text = LocalOcr.recognize(InstrumentationRegistry.getInstrumentation().getTargetContext(), buffer.toByteArray()).toUpperCase(Locale.ROOT);
        assertTrue("English text missing from OCR result: " + text, text.contains("DOCUMENT"));
        assertTrue("Russian text missing from OCR result: " + text, text.contains("ДОКУМЕНТ"));
    }
    @Test public void recognizesTextAndExpiryOnUnevenBackground() throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(1800, 600, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 1800, 0, Color.rgb(170, 170, 170), Color.WHITE, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 1800, 600, paint); paint.setShader(null); paint.setColor(Color.BLACK);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); paint.setTextSize(85);
        canvas.drawText("ДЕЙСТВУЕТ ДО 31.12.2030", 50, 240, paint);
        canvas.drawText("DOCUMENT 2030", 50, 400, paint);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)); bitmap.recycle();
        String result = LocalOcr.recognizeImage(InstrumentationRegistry.getInstrumentation().getTargetContext(), buffer.toByteArray(), false).text;
        assertTrue("Missing English: " + result, result.toUpperCase(Locale.ROOT).contains("DOCUMENT"));
        assertEquals("Expiry not found: " + result, java.time.LocalDate.of(2030, 12, 31), DateSuggestions.findExpiry(result));
    }
    @Test public void recognizesSparseFormFromTopToBottom() throws Exception {
        Bitmap image = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image); canvas.drawColor(Color.rgb(230, 228, 220));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); paint.setTextSize(65);
        canvas.drawText("DOCUMENT 2030", 70, 150, paint); canvas.drawText("ВОПРОС", 70, 570, paint); canvas.drawText("МЕТОДИКА", 70, 1070, paint);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); image.recycle();
        LocalOcr.ImageResult result = LocalOcr.recognizeImage(InstrumentationRegistry.getInstrumentation().getTargetContext(), output.toByteArray(), false);
        String text = result.text.toUpperCase(Locale.ROOT);
        assertTrue("Missing top text: " + text, text.contains("DOCUMENT"));
        assertTrue("Missing middle text: " + text, text.contains("ВОПРОС"));
        assertTrue("Missing bottom text: " + text, text.contains("МЕТОДИКА"));
        org.json.JSONArray words = result.layout.getJSONArray("words"); boolean lowerWord = false;
        for (int i = 0; i < words.length(); i++) if (words.getJSONArray(i).optString(0).toUpperCase(Locale.ROOT).contains("МЕТОДИКА") && words.getJSONArray(i).getDouble(2) > 0.7) lowerWord = true;
        assertTrue("Bottom text should have overlay coordinates", lowerWord);
    }
    @Test public void testRecognizesSyntheticPdf() throws Exception {
        android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
        android.graphics.pdf.PdfDocument.Page page = pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(800, 300, 1).create());
        Canvas canvas = page.getCanvas(); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); paint.setTextSize(65);
        canvas.drawText("DOCUMENT 2030", 30, 130, paint); pdf.finishPage(page);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); pdf.writeTo(buffer); pdf.close();
        String text = LocalOcr.recognizePdf(InstrumentationRegistry.getInstrumentation().getTargetContext(), buffer.toByteArray()).toUpperCase(Locale.ROOT);
        assertTrue("PDF text missing from OCR result: " + text, text.contains("DOCUMENT"));
    }
    @Test public void testAllExifOrientations() {
        int[] pixels = {0xff010101, 0xff020202, 0xff030303, 0xff040404, 0xff050505, 0xff060606};
        int[][] expected = {
            {1, 2, 3, 4, 5, 6}, {3, 2, 1, 6, 5, 4}, {6, 5, 4, 3, 2, 1}, {4, 5, 6, 1, 2, 3},
            {1, 4, 2, 5, 3, 6}, {4, 1, 5, 2, 6, 3}, {6, 3, 5, 2, 4, 1}, {3, 6, 2, 5, 1, 4}
        };
        for (int orientation = 1; orientation <= 8; orientation++) {
            Bitmap source = Bitmap.createBitmap(pixels, 3, 2, Bitmap.Config.ARGB_8888);
            Bitmap actual = LocalOcr.orientBitmap(source, orientation);
            try {
                assertEquals(orientation <= 4 ? 3 : 2, actual.getWidth());
                assertEquals(orientation <= 4 ? 2 : 3, actual.getHeight());
                for (int index = 0; index < 6; index++)
                    assertEquals("orientation " + orientation + " pixel " + index, expected[orientation - 1][index], actual.getPixel(index % actual.getWidth(), index / actual.getWidth()) & 0xff);
            } finally { if (actual != source) actual.recycle(); source.recycle(); }
        }
    }
    @Test public void testRecognizesRotatedJpegWithExif() throws Exception {
        Bitmap upright = Bitmap.createBitmap(1600, 400, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(upright); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); paint.setTextSize(100);
        canvas.drawText("DOCUMENT 2030", 30, 200, paint);
        Matrix rotation = new Matrix(); rotation.setRotate(-90);
        Bitmap sideways = Bitmap.createBitmap(upright, 0, 0, upright.getWidth(), upright.getHeight(), rotation, true);
        File file = File.createTempFile("synthetic-exif-", ".jpg", InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(file)) { assertTrue(sideways.compress(Bitmap.CompressFormat.JPEG, 95, output)); }
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90)); exif.saveAttributes();
            String text = LocalOcr.recognize(InstrumentationRegistry.getInstrumentation().getTargetContext(), Files.readAllBytes(file.toPath())).toUpperCase(Locale.ROOT);
            assertTrue("Rotated JPEG text missing from OCR result: " + text, text.contains("DOCUMENT"));
        } finally { file.delete(); sideways.recycle(); upright.recycle(); }
    }
}
