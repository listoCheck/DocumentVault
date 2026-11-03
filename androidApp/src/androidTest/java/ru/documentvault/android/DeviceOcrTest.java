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
