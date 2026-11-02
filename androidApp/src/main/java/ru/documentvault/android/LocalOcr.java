package ru.documentvault.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Local image OCR. PDF rendering and image orientation correction are not implemented yet. */
public final class LocalOcr {
    private LocalOcr() { }
    public static String recognize(Context context, byte[] input) throws Exception {
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
}
