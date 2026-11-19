package ru.documentvault.desktop

import com.fasterxml.jackson.databind.JsonNode

/** Coordinates are normalized against the displayed (possibly rectified) page. */
internal class OcrTextSelection(layout: JsonNode) {
    data class Word(val text: String, val left: Double, val top: Double, val right: Double, val bottom: Double)
    val words = layout.path("words").take(3000).mapNotNull { value ->
        if (!value.isArray || value.size() != 5 || !value[0].isTextual || value[0].asText().isBlank()) return@mapNotNull null
        val p = DoubleArray(4) { value[it + 1].asDouble(Double.NaN) }
        if (p.any { !it.isFinite() || it !in 0.0..1.0 } || p[2] <= p[0] || p[3] <= p[1]) null
        else Word(value[0].asText(), p[0], p[1], p[2], p[3])
    }
    fun hit(x: Double, y: Double): Int = words.indexOfFirst { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }
    fun nearest(x: Double, y: Double): Int = words.indices.minByOrNull { i ->
        val w = words[i]; val dx = (x - (w.left + w.right) / 2); val dy = (y - (w.top + w.bottom) / 2)
        dx * dx + dy * dy
    } ?: -1
    fun selected(start: Int, end: Int): List<Word> = if (start in words.indices && end in words.indices) words.subList(minOf(start, end), maxOf(start, end) + 1) else emptyList()
    fun text(start: Int, end: Int): String {
        val selected = selected(start, end); val result = StringBuilder()
        selected.forEachIndexed { index, word ->
            if (index > 0) {
                val previous = selected[index - 1]
                result.append(if (kotlin.math.abs((previous.top + previous.bottom) - (word.top + word.bottom)) > maxOf(previous.bottom - previous.top, word.bottom - word.top)) '\n' else ' ')
            }
            result.append(word.text)
        }
        return result.toString()
    }
}
