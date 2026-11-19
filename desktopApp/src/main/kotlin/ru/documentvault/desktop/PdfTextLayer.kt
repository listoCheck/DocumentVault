package ru.documentvault.desktop

import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition

/** Text positions are mapped to the displayed, rotated crop box. */
internal object PdfTextLayer {
    fun page(document: PDDocument, index: Int): ObjectNode {
        val page = document.getPage(index); val crop = page.cropBox
        val rotation = ((page.rotation % 360) + 360) % 360
        val width = if (rotation == 90 || rotation == 270) crop.height.toDouble() else crop.width.toDouble()
        val height = if (rotation == 90 || rotation == 270) crop.width.toDouble() else crop.height.toDouble()
        val json = com.fasterxml.jackson.databind.ObjectMapper(); val words = json.createArrayNode()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                var value = StringBuilder(); var left = 0.0; var top = 0.0; var right = 0.0; var bottom = 0.0; var previous: TextPosition? = null
                fun flush() {
                    if (value.isNotEmpty() && width > 0 && height > 0 && words.size() < 3000) {
                        val box = when (rotation) {
                            90 -> doubleArrayOf(crop.height - bottom, left, crop.height - top, right)
                            180 -> doubleArrayOf(crop.width - right, crop.height - bottom, crop.width - left, crop.height - top)
                            270 -> doubleArrayOf(top, crop.width - right, bottom, crop.width - left)
                            else -> doubleArrayOf(left, top, right, bottom)
                        }
                        val x1 = (box[0] / width).coerceIn(0.0, 1.0); val y1 = (box[1] / height).coerceIn(0.0, 1.0); val x2 = (box[2] / width).coerceIn(0.0, 1.0); val y2 = (box[3] / height).coerceIn(0.0, 1.0)
                        if (x2 > x1 && y2 > y1) words.add(json.createArrayNode().add(value.toString()).add(x1).add(y1).add(x2).add(y2))
                    }
                    value = StringBuilder(); previous = null
                }
                for (p in positions) {
                    val s = p.unicode.orEmpty(); if (s.isBlank()) { flush(); continue }
                    val x = p.xDirAdj.toDouble(); val y = p.yDirAdj.toDouble(); val w = p.widthDirAdj.toDouble(); val h = p.heightDir.toDouble().coerceAtLeast(1.0)
                    val prev = previous
                    if (prev != null && (x - (prev.xDirAdj + prev.widthDirAdj) > maxOf(1.0, p.widthOfSpace * 0.35) || kotlin.math.abs(y - prev.yDirAdj) > h)) flush()
                    if (value.isEmpty()) { left = x; top = y - h; right = x + w; bottom = y }
                    else { left = minOf(left, x); top = minOf(top, y - h); right = maxOf(right, x + w); bottom = maxOf(bottom, y) }
                    value.append(s); previous = p
                }
                flush()
            }
        }
        stripper.sortByPosition = true; stripper.startPage = index + 1; stripper.endPage = index + 1; stripper.getText(document)
        return json.createObjectNode().put("version", 1).also { it.set<com.fasterxml.jackson.databind.JsonNode>("words", words) }
    }
}
