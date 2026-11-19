package ru.documentvault.desktop

import java.awt.Rectangle
import java.awt.image.BufferedImage

/** Heuristic: identify boxes whose foreground is predominantly blue ink. */
internal object ColoredInk {
    fun mostlyBlue(image: BufferedImage, rect: Rectangle): Boolean {
        val x0 = rect.x.coerceIn(0, image.width); val x1 = (rect.x + rect.width).coerceIn(0, image.width)
        val y0 = rect.y.coerceIn(0, image.height); val y1 = (rect.y + rect.height).coerceIn(0, image.height)
        if (x1 <= x0 || y1 <= y0) return false
        val step = maxOf(1, maxOf(x1 - x0, y1 - y0) / 80); val histogram = IntArray(256); var samples = 0
        for (y in y0 until y1 step step) for (x in x0 until x1 step step) { val p = image.getRGB(x, y); histogram[((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000]++; samples++ }
        val target = samples * 70 / 100; var total = 0; var background = 255
        for (i in histogram.indices) { total += histogram[i]; if (total >= target) { background = i; break } }
        var blue = 0; var foreground = 0
        for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
            val p = image.getRGB(x, y); val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
            val luminance = (r * 299 + g * 587 + b * 114) / 1000
            if (luminance < background - 12) { foreground++; if (b > r + 8 && b > g + 3) blue++ }
        }
        return blue >= 3 && blue * 2 >= foreground
    }
}
