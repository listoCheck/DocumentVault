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

    ZoomImageView(Context context) {
        super(context); setScaleType(ScaleType.MATRIX); setContentDescription("Просмотр документа. Для масштаба используйте два пальца или кнопки.");
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) { scaleBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true; }
        });
        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent event) { if (zoom > 1.05f) resetZoom(); else scaleBy(2f, event.getX(), event.getY()); return true; }
        });
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
                if (dragging && !scaleDetector.isInProgress() && zoom > 1f) { transform.postTranslate(event.getX() - lastX, event.getY() - lastY); constrain(); setImageMatrix(transform); }
                lastX = event.getX(); lastY = event.getY(); break;
            case MotionEvent.ACTION_POINTER_UP: dragging = false; break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                dragging = false; getParent().requestDisallowInterceptTouchEvent(false); if (event.getActionMasked() == MotionEvent.ACTION_UP && !doubleTap) performClick(); break;
        }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
