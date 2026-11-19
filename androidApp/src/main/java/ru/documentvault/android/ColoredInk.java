package ru.documentvault.android;

import android.graphics.Bitmap;
import android.graphics.Color;

/** Heuristic: identify boxes whose foreground is predominantly blue ink. */
final class ColoredInk {
    private ColoredInk() { }
    static boolean mostlyBlue(Bitmap image, int[] box) {
        int x0 = Math.max(0, box[0]), y0 = Math.max(0, box[1]), x1 = Math.min(image.getWidth(), box[2]), y1 = Math.min(image.getHeight(), box[3]);
        if (x1 <= x0 || y1 <= y0) return false;
        int step = Math.max(1, Math.max(x1 - x0, y1 - y0) / 80), samples = 0; int[] histogram = new int[256];
        for (int y = y0; y < y1; y += step) for (int x = x0; x < x1; x += step) { int p = image.getPixel(x, y); histogram[(Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000]++; samples++; }
        int target = samples * 70 / 100, total = 0, background = 255;
        for (int i = 0; i < histogram.length; i++) { total += histogram[i]; if (total >= target) { background = i; break; } }
        int blue = 0, foreground = 0;
        for (int y = y0; y < y1; y += step) for (int x = x0; x < x1; x += step) {
            int p = image.getPixel(x, y), r = Color.red(p), g = Color.green(p), b = Color.blue(p), light = (r * 299 + g * 587 + b * 114) / 1000;
            if (light < background - 12) { foreground++; if (b > r + 8 && b > g + 3) blue++; }
        }
        return blue >= 3 && blue * 2 >= foreground;
    }
}
