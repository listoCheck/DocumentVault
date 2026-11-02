package ru.documentvault.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import java.io.ByteArrayOutputStream;
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
}
