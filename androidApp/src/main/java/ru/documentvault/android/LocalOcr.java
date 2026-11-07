package ru.documentvault.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;
import com.googlecode.tesseract.android.TessBaseAPI;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Local OCR. Temporary PDF copies stay in the app-private cache. */
public final class LocalOcr {
    public interface Progress { void onPage(int current, int total); }
    public static final class ImageResult {
        public final String text; public final org.json.JSONObject layout; public final boolean cropped;
        ImageResult(String text, org.json.JSONObject layout, boolean cropped) { this.text = text; this.layout = layout; this.cropped = cropped; }
    }
    private LocalOcr() { }
    public static String recognize(Context context, byte[] input) throws Exception { return recognizeImage(context, input).text; }
    static Bitmap decodeImage(byte[] input) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(input, 0, input.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IllegalArgumentException("Неподдерживаемое изображение");
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1; while (((long) Math.max(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > 3000) sample *= 2;
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeByteArray(input, 0, input.length, options);
        if (decoded == null) throw new IllegalArgumentException("Не удалось открыть изображение");
        Bitmap bitmap;
        try { bitmap = orientBitmap(decoded, readOrientation(input)); }
        catch (Throwable error) { decoded.recycle(); throw error; }
        if (bitmap != decoded) decoded.recycle();
        return bitmap;
    }
    public static ImageResult recognizeImage(Context context, byte[] input) throws Exception { return recognizeImage(context, input, true); }
    public static ImageResult recognizeImage(Context context, byte[] input, boolean detectPage) throws Exception {
        File root = prepareModels(context);
        Bitmap bitmap = decodeImage(input);
        TessBaseAPI tess = new TessBaseAPI();
        DocumentScan.Result scan = null;
        try {
            if (!tess.init(root.getAbsolutePath(), "rus+eng")) throw new IllegalStateException("Не удалось запустить OCR");
            scan = detectPage ? DocumentScan.scan(bitmap, null) : new DocumentScan.Result(bitmap.copy(Bitmap.Config.ARGB_8888, false), null);
            Bitmap prepared = prepareImage(scan.image);
            try {
                java.util.List<RecognizedWord> found = new java.util.ArrayList<>();
                for (int mode : new int[]{TessBaseAPI.PageSegMode.PSM_AUTO, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT}) {
                    tess.setPageSegMode(mode); recognizeBest(tess, prepared); collectWords(tess, prepared, found);
                }
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                found.sort((left, right) -> Double.compare(left.centerY(), right.centerY()));
                java.util.List<java.util.List<RecognizedWord>> lines = new java.util.ArrayList<>();
                for (RecognizedWord word : found) {
                    java.util.List<RecognizedWord> chosen = null;
                    for (java.util.List<RecognizedWord> line : lines) {
                        for (RecognizedWord existing : line) {
                            if (Math.abs(existing.centerY() - word.centerY()) <= Math.max(existing.height(), word.height()) * 0.55) { chosen = line; break; }
                        }
                        if (chosen != null) break;
                    }
                    if (chosen == null) { chosen = new java.util.ArrayList<>(); lines.add(chosen); }
                    chosen.add(word);
                }
                StringBuilder clean = new StringBuilder(); org.json.JSONArray words = new org.json.JSONArray();
                for (java.util.List<RecognizedWord> line : lines) {
                    line.sort((left, right) -> Integer.compare(left.box[0], right.box[0])); boolean lineHasText = false;
                    for (RecognizedWord word : line) {
                        String value = keepWord(word.value, word.confidence) ? word.value.trim() : "";
                        if (!value.isEmpty()) { if (!lineHasText && clean.length() > 0) clean.append('\n'); else if (lineHasText) clean.append(' '); clean.append(value); lineHasText = true; }
                        org.json.JSONArray entry = new org.json.JSONArray(); entry.put(value);
                        entry.put((double) word.box[0] / prepared.getWidth()); entry.put((double) word.box[1] / prepared.getHeight());
                        entry.put((double) word.box[2] / prepared.getWidth()); entry.put((double) word.box[3] / prepared.getHeight()); words.put(entry);
                    }
                }
                org.json.JSONObject layout = new org.json.JSONObject().put("version", 1).put("words", words);
                if (scan.corners != null) { org.json.JSONArray corners = new org.json.JSONArray(); for (float v : scan.corners) corners.put((double) v); layout.put("corners", corners); }
                return new ImageResult(clean.toString(), layout, scan.corners != null);
            }
            finally { prepared.recycle(); }
        } finally { tess.recycle(); if (scan != null) scan.image.recycle(); bitmap.recycle(); }
    }

    private static final class RecognizedWord {
        final String value; final int[] box; final float confidence;
        RecognizedWord(String value, int[] box, float confidence) { this.value = value; this.box = box; this.confidence = confidence; }
        int height() { return box[3] - box[1]; }
        double centerY() { return (box[1] + box[3]) / 2.0; }
    }
    private static boolean overlaps(int[] a, int[] b) {
        int width = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]));
        int height = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
        double minimumArea = Math.min((double) (a[2]-a[0]) * (a[3]-a[1]), (double) (b[2]-b[0]) * (b[3]-b[1]));
        return minimumArea > 0 && width * (double) height / minimumArea >= 0.6;
    }
    private static void collectWords(TessBaseAPI tess, Bitmap image, java.util.List<RecognizedWord> found) {
        com.googlecode.tesseract.android.ResultIterator it = tess.getResultIterator(); if (it == null) return;
        try {
            it.begin(); int level = TessBaseAPI.PageIteratorLevel.RIL_WORD;
            do {
                String value = it.getUTF8Text(level); int[] box = it.getBoundingBox(level);
                if (value == null || value.trim().isEmpty() || box == null || box.length != 4 || box[0] < 0 || box[1] < 0 || box[2] > image.getWidth() || box[3] > image.getHeight() || box[2] <= box[0] || box[3] <= box[1]) continue;
                RecognizedWord word = new RecognizedWord(value, box, it.confidence(level)); int duplicate = -1;
                for (int i = 0; i < found.size(); i++) if (overlaps(found.get(i).box, box)) { duplicate = i; break; }
                if (duplicate < 0 && found.size() < 3000) found.add(word);
                else if (duplicate >= 0 && word.confidence > found.get(duplicate).confidence + 5) found.set(duplicate, word);
            } while (found.size() < 3000 && it.next(level));
        } finally { it.delete(); }
    }

    private static int readOrientation(byte[] input) {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(input)) {
            return new ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (IOException error) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    static Bitmap orientBitmap(Bitmap bitmap, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: return bitmap;
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    public static String recognizePdf(Context context, byte[] input) throws Exception { return recognizePdf(context, input, null); }
    public static String recognizePdf(Context context, byte[] input, Progress progress) throws Exception {
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
                    if (progress != null) progress.onPage(index + 1, pdf.getPageCount());
                    try (PdfRenderer.Page page = pdf.openPage(index)) {
                        float scale = Math.min(2f, 3000f / Math.max(page.getWidth(), page.getHeight()));
                        int width = Math.max(1, Math.round(page.getWidth() * scale));
                        int height = Math.max(1, Math.round(page.getHeight() * scale));
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        try {
                            bitmap.eraseColor(Color.WHITE);
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            Bitmap prepared = prepareImage(bitmap);
                            String text;
                            try { recognizeBest(tess, prepared); text = cleanRecognizedText(tess); } finally { prepared.recycle(); }
                            if (text != null) result.append(text).append('\n');
                        } finally { bitmap.recycle(); }
                    }
                }
            }
            return result.toString();
        } finally { tess.recycle(); temporary.delete(); }
    }

    private static String cleanRecognizedText(TessBaseAPI tess) {
        StringBuilder text = new StringBuilder();
        com.googlecode.tesseract.android.ResultIterator it = tess.getResultIterator();
        if (it == null) return "";
        try {
            it.begin(); int level = TessBaseAPI.PageIteratorLevel.RIL_WORD;
            do {
                String word = it.getUTF8Text(level);
                if (word == null || word.trim().isEmpty() || !keepWord(word, it.confidence(level))) continue;
                if (text.length() > 0) text.append(it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE) ? '\n' : ' ');
                text.append(word.trim());
            } while (it.next(level));
            return text.toString();
        } finally { it.delete(); }
    }
    private static boolean keepWord(String word, float confidence) {
        String trimmed = word.trim();
        if (trimmed.matches(".*\\d.*")) return true;
        if (!trimmed.matches(".*[\\p{L}\\p{N}].*")) return false;
        return confidence >= 15f;
    }
    /** Compare the regular scan with an adaptive threshold for uneven illumination. */
    private static String recognizeBest(TessBaseAPI tess, Bitmap input) {
        tess.setImage(input); String original = tess.getUTF8Text(); int originalConfidence = tess.meanConfidence();
        if (originalConfidence >= 80 || input.getWidth() < 50 || input.getHeight() < 50) return original;
        Bitmap binary = null; org.opencv.core.Mat rgba = new org.opencv.core.Mat(), gray = new org.opencv.core.Mat();
        try {
            if (!org.opencv.android.OpenCVLoader.initLocal()) return original;
            org.opencv.android.Utils.bitmapToMat(input, rgba);
            org.opencv.imgproc.Imgproc.cvtColor(rgba, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY);
            org.opencv.imgproc.Imgproc.adaptiveThreshold(gray, gray, 255, org.opencv.imgproc.Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, org.opencv.imgproc.Imgproc.THRESH_BINARY, 31, 15);
            binary = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);
            org.opencv.android.Utils.matToBitmap(gray, binary);
            tess.setImage(binary); String alternative = tess.getUTF8Text(); int confidence = tess.meanConfidence();
            int originalLength = original == null ? 0 : original.trim().length();
            int alternativeLength = alternative == null ? 0 : alternative.trim().length();
            if (confidence > originalConfidence + 3 && alternativeLength >= originalLength * 0.8) return alternative;
            tess.setImage(input); return tess.getUTF8Text();
        } catch (RuntimeException | LinkageError ignored) {
            tess.setImage(input); return tess.getUTF8Text();
        } finally { rgba.release(); gray.release(); if (binary != null) binary.recycle(); }
    }
    private static Bitmap prepareImage(Bitmap source) {
        float scale = Math.min(2f, Math.max(1f, 1600f / Math.max(source.getWidth(), source.getHeight())));
        Bitmap result = Bitmap.createBitmap(Math.max(1, Math.round(source.getWidth() * scale)), Math.max(1, Math.round(source.getHeight() * scale)), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(result); canvas.drawColor(Color.WHITE);
        android.graphics.ColorMatrix grayscale = new android.graphics.ColorMatrix(); grayscale.setSaturation(0f);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG); paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(grayscale));
        canvas.drawBitmap(source, null, new android.graphics.Rect(0, 0, result.getWidth(), result.getHeight()), paint);
        return result;
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
