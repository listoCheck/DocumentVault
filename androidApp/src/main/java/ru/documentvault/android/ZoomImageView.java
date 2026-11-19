package ru.documentvault.android;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** Fit-to-screen image with pinch, drag and accessible button zoom. */
final class ZoomImageView extends ImageView {
    private final Matrix transform = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector tapDetector;
    private float baseScale = 1f, zoom = 1f, lastX, lastY;
    private boolean dragging;
    private final java.util.List<Word> words = new java.util.ArrayList<>();
    private int selectionStart = -1, selectionEnd = -1;
    private boolean selecting;
    private android.view.ActionMode selectionMode;
    private static final class Word {
        final String text; final RectF bounds;
        Word(String text, RectF bounds) { this.text = text; this.bounds = bounds; }
    }

    ZoomImageView(Context context) {
        super(context); setScaleType(ScaleType.MATRIX); setContentDescription("Просмотр документа. Для масштаба используйте два пальца или кнопки.");
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) { scaleBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true; }
        });
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent event) { if (zoom > 1.05f) resetZoom(); else scaleBy(2f, event.getX(), event.getY()); return true; }
            @Override public void onLongPress(MotionEvent event) {
                int hit = hitWord(event.getX(), event.getY(), false); if (hit < 0) return;
                if (selectionMode != null) selectionMode.finish();
                selectionStart = selectionEnd = hit; selecting = true; dragging = false; invalidate();
                selectionMode = startActionMode(new android.view.ActionMode.Callback() {
                    @Override public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) { menu.add("Копировать").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS); return true; }
                    @Override public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) { return false; }
                    @Override public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                        String text = selectedText();
                        if (!text.isEmpty()) ((android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("OCR", text));
                        mode.finish(); return true;
                    }
                    @Override public void onDestroyActionMode(android.view.ActionMode mode) { selectionMode = null; selectionStart = selectionEnd = -1; invalidate(); }
                });
            }
        });
    }
    void setOcrLayout(org.json.JSONObject layout) {
        words.clear(); selectionStart = selectionEnd = -1;
        if (layout == null) return;
        org.json.JSONArray values = layout.optJSONArray("words"); if (values == null) return;
        for (int i = 0; i < Math.min(values.length(), 3000); i++) {
            org.json.JSONArray item = values.optJSONArray(i); if (item == null || item.length() != 5) continue;
            String value = item.optString(0, "").trim(); float l = (float) item.optDouble(1, Double.NaN), t = (float) item.optDouble(2, Double.NaN), r = (float) item.optDouble(3, Double.NaN), b = (float) item.optDouble(4, Double.NaN);
            if (!value.isEmpty() && Float.isFinite(l) && Float.isFinite(t) && Float.isFinite(r) && Float.isFinite(b) && l >= 0 && t >= 0 && r <= 1 && b <= 1 && r > l && b > t) words.add(new Word(value, new RectF(l, t, r, b)));
        }
    }
    private int hitWord(float x, float y, boolean nearest) {
        Drawable drawable = getDrawable(); if (drawable == null) return -1;
        Matrix inverse = new Matrix(); if (!transform.invert(inverse)) return -1;
        float[] point = {x, y}; inverse.mapPoints(point); point[0] /= drawable.getIntrinsicWidth(); point[1] /= drawable.getIntrinsicHeight();
        int best = -1; float distance = Float.MAX_VALUE;
        for (int i = 0; i < words.size(); i++) { RectF b = words.get(i).bounds;
            if (b.contains(point[0], point[1])) return i;
            if (nearest) { float dx = point[0] - b.centerX(), dy = point[1] - b.centerY(), d = dx * dx + dy * dy; if (d < distance) { distance = d; best = i; } }
        } return best;
    }
    String selectedText() {
        if (selectionStart < 0 || selectionEnd < 0) return "";
        StringBuilder text = new StringBuilder(); Word previous = null;
        for (int i = Math.min(selectionStart, selectionEnd); i <= Math.max(selectionStart, selectionEnd); i++) {
            Word word = words.get(i);
            if (previous != null) text.append(Math.abs(previous.bounds.centerY() - word.bounds.centerY()) > Math.max(previous.bounds.height(), word.bounds.height()) * .5f ? '\n' : ' ');
            text.append(word.text); previous = word;
        } return text.toString();
    }
    @Override protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas); Drawable drawable = getDrawable(); if (drawable == null || selectionStart < 0 || selectionEnd < 0) return;
        android.graphics.Paint paint = new android.graphics.Paint(); paint.setColor(android.graphics.Color.argb(95, 100, 150, 255));
        for (int i = Math.min(selectionStart, selectionEnd); i <= Math.max(selectionStart, selectionEnd); i++) {
            RectF b = new RectF(words.get(i).bounds); b.left *= drawable.getIntrinsicWidth(); b.right *= drawable.getIntrinsicWidth(); b.top *= drawable.getIntrinsicHeight(); b.bottom *= drawable.getIntrinsicHeight(); transform.mapRect(b); canvas.drawRect(b, paint);
        }
    }
    @Override public void setImageBitmap(android.graphics.Bitmap bitmap) { super.setImageBitmap(bitmap); resetZoom(); }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) { super.onSizeChanged(width, height, oldWidth, oldHeight); resetZoom(); }
    void resetZoom() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0 || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) return;
        float dw = drawable.getIntrinsicWidth(), dh = drawable.getIntrinsicHeight();
        baseScale = Math.min(getWidth() / dw, getHeight() / dh); zoom = 1f;
        transform.reset(); transform.setScale(baseScale, baseScale);
        transform.postTranslate((getWidth() - dw * baseScale) / 2f, (getHeight() - dh * baseScale) / 2f);
        setImageMatrix(transform);
    }
    void zoomIn() { scaleBy(1.4f, getWidth() / 2f, getHeight() / 2f); }
    void zoomOut() { scaleBy(1f / 1.4f, getWidth() / 2f, getHeight() / 2f); }
    float zoomLevel() { return zoom; }
    private void scaleBy(float factor, float x, float y) {
        if (getDrawable() == null || getWidth() <= 0 || getHeight() <= 0 || !Float.isFinite(factor)) return;
        float next = Math.max(1f, Math.min(5f, zoom * factor));
        transform.postScale(next / zoom, next / zoom, x, y); zoom = next; constrain(); setImageMatrix(transform); invalidate();
    }
    private void constrain() {
        Drawable drawable = getDrawable(); if (drawable == null) return;
        RectF rect = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight()); transform.mapRect(rect);
        float dx = rect.width() <= getWidth() ? (getWidth() - rect.width()) / 2f - rect.left : rect.left > 0 ? -rect.left : rect.right < getWidth() ? getWidth() - rect.right : 0;
        float dy = rect.height() <= getHeight() ? (getHeight() - rect.height()) / 2f - rect.top : rect.top > 0 ? -rect.top : rect.bottom < getHeight() ? getHeight() - rect.bottom : 0;
        transform.postTranslate(dx, dy);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event); boolean doubleTap = tapDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true); lastX = event.getX(); lastY = event.getY(); dragging = true; break;
            case MotionEvent.ACTION_POINTER_DOWN: dragging = false; break;
            case MotionEvent.ACTION_MOVE:
                if (selecting && !scaleDetector.isInProgress()) { int hit = hitWord(event.getX(), event.getY(), true); if (hit >= 0) { selectionEnd = hit; invalidate(); } break; }
                if (dragging && !scaleDetector.isInProgress() && zoom > 1f) { transform.postTranslate(event.getX() - lastX, event.getY() - lastY); constrain(); setImageMatrix(transform); }
                lastX = event.getX(); lastY = event.getY(); break;
            case MotionEvent.ACTION_POINTER_UP: dragging = false; break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                dragging = false; selecting = false; getParent().requestDisallowInterceptTouchEvent(false); if (event.getActionMasked() == MotionEvent.ACTION_UP && !doubleTap) performClick(); break;
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
