package ru.documentvault.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Detects a likely page and rectifies it without changing the stored original. */
final class DocumentScan {
    static final class Result { final Bitmap image; final float[] corners; Result(Bitmap image, float[] corners) { this.image = image; this.corners = corners; } }
    private DocumentScan() { }
    static Result scan(Bitmap source, float[] savedCorners) {
        float[] corners = savedCorners;
        if (corners == null) try { corners = detect(source); } catch (RuntimeException | LinkageError ignored) { corners = null; }
        if (!valid(corners)) return new Result(source.copy(Bitmap.Config.ARGB_8888, false), null);
        float w = source.getWidth(), h = source.getHeight(); float[] src = new float[8];
        for (int i = 0; i < 4; i++) { src[i * 2] = corners[i * 2] * w; src[i * 2 + 1] = corners[i * 2 + 1] * h; }
        float width = Math.max(distance(src, 0, 1), distance(src, 3, 2));
        float height = Math.max(distance(src, 0, 3), distance(src, 1, 2));
        if (width < 40 || height < 40) return new Result(source.copy(Bitmap.Config.ARGB_8888, false), null);
        float scale = Math.min(1f, 2400f / Math.max(width, height));
        int outWidth = Math.max(1, Math.round(width * scale)), outHeight = Math.max(1, Math.round(height * scale));
        float[] dst = {0, 0, outWidth, 0, outWidth, outHeight, 0, outHeight}; Matrix matrix = new Matrix();
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return new Result(source.copy(Bitmap.Config.ARGB_8888, false), null);
        Bitmap output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(output); canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, matrix, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG)); return new Result(output, corners);
    }
    private static float distance(float[] p, int a, int b) { return (float) Math.hypot(p[a * 2] - p[b * 2], p[a * 2 + 1] - p[b * 2 + 1]); }
    private static boolean valid(float[] p) { if (p == null || p.length != 8) return false; for (float v : p) if (!Float.isFinite(v) || v < 0 || v > 1) return false; return true; }
    private static float[] detect(Bitmap image) {
        if (!OpenCVLoader.initLocal()) return null;
        int width = image.getWidth(), height = image.getHeight(); float scale = Math.min(1f, 1000f / Math.max(width, height));
        Bitmap small = Bitmap.createScaledBitmap(image, Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), true);
        Mat rgba = new Mat(), gray = new Mat(), edges = new Mat(), hierarchy = new Mat(), kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 5)); List<MatOfPoint> contours = new ArrayList<>();
        try {
            Utils.bitmapToMat(small, rgba); Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY); Imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);
            Imgproc.Canny(gray, edges, 40, 120);
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel); Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE);
            double area = small.getWidth() * (double) small.getHeight(), bestArea = area * 0.20; Point[] best = null;
            for (MatOfPoint contour : contours) {
                double candidate = Math.abs(Imgproc.contourArea(contour)); if (candidate <= bestArea || candidate > area * 0.98) continue;
                MatOfPoint2f curve = new MatOfPoint2f(contour.toArray()), approx = new MatOfPoint2f();
                try {
                    Imgproc.approxPolyDP(curve, approx, 0.02 * Imgproc.arcLength(curve, true), true); Point[] points = approx.toArray();
                    if (points.length != 4) continue;
                    MatOfPoint polygon = new MatOfPoint(points); boolean convex; try { convex = Imgproc.isContourConvex(polygon); } finally { polygon.release(); }
                    if (convex) { bestArea = candidate; best = points; }
                } finally { curve.release(); approx.release(); }
            }
            if (best == null) return null;
            Arrays.sort(best, (a, b) -> Double.compare(a.y, b.y));
            Point topLeft = best[0].x < best[1].x ? best[0] : best[1], topRight = best[0].x < best[1].x ? best[1] : best[0];
            Point bottomLeft = best[2].x < best[3].x ? best[2] : best[3], bottomRight = best[2].x < best[3].x ? best[3] : best[2];
            Point[] ordered = {topLeft, topRight, bottomRight, bottomLeft}; float[] result = new float[8];
            for (int i = 0; i < 4; i++) { result[i * 2] = (float) (ordered[i].x / small.getWidth()); result[i * 2 + 1] = (float) (ordered[i].y / small.getHeight()); }
            return valid(result) ? result : null;
        } finally { for (MatOfPoint contour : contours) contour.release(); rgba.release(); gray.release(); edges.release(); hierarchy.release(); kernel.release(); if (small != image) small.recycle(); }
    }
}
