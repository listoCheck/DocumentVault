package ru.documentvault.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Local OCR. Temporary PDF copies stay in the app-private cache. */
public final class LocalOcr {
    private LocalOcr() { }
    public static String recognize(Context context, byte[] input) throws Exception {
        File root = prepareModels(context);
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(input, 0, input.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("Неподдерживаемое изображение");
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1; while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2;
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeByteArray(input, 0, input.length, options);
        if (bitmap == null) throw new IllegalArgumentException("Не удалось открыть изображение");
        TessBaseAPI tess = new TessBaseAPI();
        try {
            if (!tess.init(root.getAbsolutePath(), "rus+eng")) throw new IllegalStateException("Не удалось запустить OCR");
            tess.setImage(bitmap);
            String result = tess.getUTF8Text();
            return result == null ? "" : result;
        } finally { tess.recycle(); bitmap.recycle(); }
    }

    public static String recognizePdf(Context context, byte[] input) throws Exception {
        File root = prepareModels(context);
        File directory = new File(context.getCacheDir(), "ocr-pdf");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Не удалось подготовить PDF");
        File temporary = File.createTempFile("document-", ".pdf", directory);
        TessBaseAPI tess = new TessBaseAPI();
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(input); }
            if (!tess.init(root.getAbsolutePath(), "rus+eng")) throw new IllegalStateException("Не удалось запустить OCR");
            StringBuilder result = new StringBuilder();
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer pdf = new PdfRenderer(descriptor)) {
                if (pdf.getPageCount() < 1 || pdf.getPageCount() > 20) throw new IllegalArgumentException("Поддерживаются PDF не более 20 страниц");
                for (int index = 0; index < pdf.getPageCount(); index++) {
                    try (PdfRenderer.Page page = pdf.openPage(index)) {
                        float scale = Math.min(2f, 3000f / Math.max(page.getWidth(), page.getHeight()));
                        int width = Math.max(1, Math.round(page.getWidth() * scale));
                        int height = Math.max(1, Math.round(page.getHeight() * scale));
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        try {
                            bitmap.eraseColor(Color.WHITE);
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            tess.setImage(bitmap);
                            String text = tess.getUTF8Text();
                            if (text != null) result.append(text).append('\n');
                        } finally { bitmap.recycle(); }
                    }
                }
            }
            return result.toString();
        } finally { tess.recycle(); temporary.delete(); }
    }

    private static File prepareModels(Context context) throws Exception {
        File root = new File(context.getFilesDir(), "ocr");
        File data = new File(root, "tessdata");
        if (!data.isDirectory() && !data.mkdirs()) throw new IllegalStateException("Не удалось подготовить языковые модели");
        for (String language : new String[]{"rus", "eng"}) {
            File dest = new File(data, language + ".traineddata");
            if (!dest.isFile() || dest.length() == 0) {
                File temp = new File(data, language + ".tmp");
                try (InputStream in = context.getAssets().open("tessdata/" + language + ".traineddata"); FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                if (!temp.renameTo(dest)) { temp.delete(); throw new IllegalStateException("Не удалось сохранить языковые модели"); }
            }
        }
        return root;
    }
}
