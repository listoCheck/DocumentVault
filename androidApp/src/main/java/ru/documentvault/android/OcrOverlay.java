package ru.documentvault.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import org.json.JSONArray;
import org.json.JSONObject;

/** Replaces OCR word regions with a gray background and fitted text. */
final class OcrOverlay {
    private OcrOverlay() { }
    static String correctedLayout(String originalLayout, String corrected) {
        if (originalLayout == null) return null;
        try {
            JSONObject layout = new JSONObject(originalLayout); JSONArray words = layout.getJSONArray("words");
            String trimmed = corrected.trim(); String[] tokens = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
            int visible = 0; for (int i = 0; i < words.length(); i++) if (!words.getJSONArray(i).optString(0, "").isEmpty()) visible++;
            if (tokens.length != visible) return null;
            int tokenIndex = 0; for (int i = 0; i < words.length(); i++) if (!words.getJSONArray(i).optString(0, "").isEmpty()) words.getJSONArray(i).put(0, tokens[tokenIndex++]);
            return layout.toString();
        } catch (org.json.JSONException error) { return null; }
    }
    static Bitmap render(Bitmap source, JSONObject layout) throws Exception {
        if (layout.optInt("version") != 1) throw new IllegalArgumentException("Неизвестный формат распознавания");
        JSONArray cornersJson = layout.optJSONArray("corners"); float[] corners = null;
        if (cornersJson != null && cornersJson.length() == 8) { corners = new float[8]; for (int i = 0; i < 8; i++) corners[i] = (float) cornersJson.getDouble(i); }
        Bitmap rectified = corners == null ? source.copy(Bitmap.Config.ARGB_8888, true) : DocumentScan.scan(source, corners).image;
        Bitmap result = rectified.copy(Bitmap.Config.ARGB_8888, true); rectified.recycle();
        try {
            Canvas canvas = new Canvas(result); Paint background = new Paint(); background.setColor(Color.rgb(224, 226, 225));
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(Color.BLACK); text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            JSONArray words = layout.getJSONArray("words");
            for (int pass = 0; pass < 2; pass++) for (int i = 0; i < Math.min(words.length(), 3000); i++) {
                JSONArray word = words.getJSONArray(i); if (word.length() != 5) continue;
                float left = (float) word.getDouble(1), top = (float) word.getDouble(2), right = (float) word.getDouble(3), bottom = (float) word.getDouble(4);
                if (!Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right) || !Float.isFinite(bottom) || left < 0 || top < 0 || right > 1 || bottom > 1 || right <= left || bottom <= top) continue;
                int l = Math.max(0, Math.round(left * result.getWidth())), t = Math.max(0, Math.round(top * result.getHeight()));
                int r = Math.min(result.getWidth(), Math.round(right * result.getWidth())), b = Math.min(result.getHeight(), Math.round(bottom * result.getHeight()));
                if (r <= l || b <= t) continue;
                if (pass == 0) {
                    int padX = Math.max(1, Math.min(3, Math.round((b - t) * 0.08f)));
                    int padY = Math.max(1, Math.min(4, Math.round((b - t) * 0.15f)));
                    canvas.drawRect(Math.max(0, l - padX), Math.max(0, t - padY), Math.min(result.getWidth(), r + padX), Math.min(result.getHeight(), b + padY), background); continue;
                }
                String value = word.optString(0, ""); if (value.isEmpty()) continue;
                text.setTextScaleX(1f); text.setTextSize(100f);
                Rect bounds = new Rect(); text.getTextBounds(value, 0, value.length(), bounds);
                if (bounds.height() <= 0) continue;
                text.setTextSize(Math.max(1f, 100f * (b - t) * 0.96f / bounds.height()));
                float measured = text.measureText(value); if (measured > r - l) text.setTextScaleX((r - l) / measured);
                text.getTextBounds(value, 0, value.length(), bounds);
                float baseline = t + ((b - t) - bounds.height()) / 2f - bounds.top;
                canvas.save(); canvas.clipRect(l, t, r, b); canvas.drawText(value, l, baseline, text); canvas.restore();
            }
            return result;
        } catch (Exception error) { result.recycle(); throw error; }
    }
}
