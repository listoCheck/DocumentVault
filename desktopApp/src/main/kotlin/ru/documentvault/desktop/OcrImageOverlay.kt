package ru.documentvault.desktop

import com.fasterxml.jackson.databind.JsonNode
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Renders the encrypted OCR coordinates shared with Android onto an oriented image. */
object OcrImageOverlay {
    fun render(source: BufferedImage, layout: JsonNode): BufferedImage {
        require(layout.path("version").asInt() == 1 && layout.path("words").isArray && layout.path("words").size() > 0) { "Нет координат текста. Распознайте изображение заново." }
        val corners = layout.path("corners")
        val output = if (corners.isArray && corners.size() == 8) rectify(source, DoubleArray(8) { corners[it].asDouble(Double.NaN) }) else {
            BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB).also { result -> result.createGraphics().also { it.drawImage(source, 0, 0, null); it.dispose() } }
        }
        try {
            val graphics = output.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                data class TextBox(val value: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
                val boxes = layout.path("words").take(3000).mapNotNull { word ->
                    if (!word.isArray || word.size() != 5 || !word[0].isTextual) return@mapNotNull null
                    val xy = DoubleArray(4) { word[it + 1].asDouble(Double.NaN) }
                    if (xy.any { !it.isFinite() || it < 0 || it > 1 } || xy[2] <= xy[0] || xy[3] <= xy[1]) return@mapNotNull null
                    val left = (xy[0] * output.width).roundToInt().coerceIn(0, output.width - 1)
                    val top = (xy[1] * output.height).roundToInt().coerceIn(0, output.height - 1)
                    val right = (xy[2] * output.width).roundToInt().coerceIn(left + 1, output.width)
                    val bottom = (xy[3] * output.height).roundToInt().coerceIn(top + 1, output.height)
                    TextBox(word[0].asText().take(200), left, top, right, bottom)
                }
                graphics.color = Color(224, 226, 225)
                for (box in boxes) {
                    val height = box.bottom - box.top; val padX = (height * 0.08).roundToInt().coerceIn(1, 3); val padY = (height * 0.15).roundToInt().coerceIn(1, 4)
                    val left = (box.left - padX).coerceAtLeast(0); val top = (box.top - padY).coerceAtLeast(0)
                    graphics.fillRect(left, top, (box.right + padX).coerceAtMost(output.width) - left, (box.bottom + padY).coerceAtMost(output.height) - top)
                }
                for (box in boxes) {
                    if (box.value.isEmpty()) continue
                    val width = box.right - box.left; val height = box.bottom - box.top
                    var font = Font("SansSerif", Font.PLAIN, 100)
                    var glyph = font.createGlyphVector(graphics.fontRenderContext, box.value)
                    val original = glyph.visualBounds; if (original.height <= 0.0) continue
                    font = font.deriveFont((100.0 * height * 0.96 / original.height).toFloat().coerceAtLeast(1f))
                    glyph = font.createGlyphVector(graphics.fontRenderContext, box.value)
                    val visual = glyph.visualBounds; if (visual.width <= 0.0 || visual.height <= 0.0) continue
                    val horizontalScale = minOf(1.0, width / visual.width)
                    val painter = graphics.create() as java.awt.Graphics2D
                    try {
                        painter.clipRect(box.left, box.top, width, height); painter.translate(box.left.toDouble(), box.top.toDouble()); painter.scale(horizontalScale, 1.0)
                        painter.color = Color.BLACK
                        painter.drawGlyphVector(glyph, (-visual.x).toFloat(), ((height - visual.height) / 2.0 - visual.y).toFloat())
                    } finally { painter.dispose() }
                }
            } finally { graphics.dispose() }
            return output
        } catch (error: Throwable) { output.flush(); throw error }
    }
    private fun rectify(source: BufferedImage, p: DoubleArray): BufferedImage {
        require(p.all { it.isFinite() && it in 0.0..1.0 }) { "Повреждены границы документа." }
        val x = DoubleArray(4) { p[it * 2] * source.width }; val y = DoubleArray(4) { p[it * 2 + 1] * source.height }
        fun distance(a: Int, b: Int) = hypot(x[a] - x[b], y[a] - y[b])
        val width = maxOf(distance(0, 1), distance(3, 2)); val height = maxOf(distance(0, 3), distance(1, 2))
        require(width >= 40 && height >= 40) { "Повреждены границы документа." }
        val scale = minOf(1.0, 2400.0 / maxOf(width, height)); val w = (width * scale).roundToInt().coerceAtLeast(1); val h = (height * scale).roundToInt().coerceAtLeast(1)
        val dx1 = x[1] - x[2]; val dx2 = x[3] - x[2]; val dx3 = x[0] - x[1] + x[2] - x[3]
        val dy1 = y[1] - y[2]; val dy2 = y[3] - y[2]; val dy3 = y[0] - y[1] + y[2] - y[3]
        val det = dx1 * dy2 - dx2 * dy1
        require(kotlin.math.abs(det) > 1e-6) { "Повреждены границы документа." }
        val g = (dx3 * dy2 - dx2 * dy3) / det; val h0 = (dx1 * dy3 - dx3 * dy1) / det
        val ax = x[1] - x[0] + g * x[1]; val bx = x[3] - x[0] + h0 * x[3]
        val ay = y[1] - y[0] + g * y[1]; val by = y[3] - y[0] + h0 * y[3]
        val input = source.getRGB(0, 0, source.width, source.height, null, 0, source.width); val out = IntArray(w * h)
        try {
            for (row in 0 until h) for (col in 0 until w) {
                val u = col.toDouble() / (w - 1).coerceAtLeast(1); val v = row.toDouble() / (h - 1).coerceAtLeast(1); val denominator = g * u + h0 * v + 1
                if (!denominator.isFinite() || kotlin.math.abs(denominator) < 1e-8) { out[row * w + col] = -1; continue }
                val sx = ((ax * u + bx * v + x[0]) / denominator).roundToInt().coerceIn(0, source.width - 1)
                val sy = ((ay * u + by * v + y[0]) / denominator).roundToInt().coerceIn(0, source.height - 1)
                out[row * w + col] = input[sy * source.width + sx]
            }
            return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, w, h, out, 0, w) }
        } finally { input.fill(0); out.fill(0) }
    }
}
