package ru.documentvault.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfTextLayerTest {
    @Test fun `word coordinates follow rotated and cropped pages`() {
        PDDocument().use { document ->
            for (rotation in listOf(0, 90, 180, 270)) {
                val page = PDPage(); page.rotation = rotation
                page.cropBox = org.apache.pdfbox.pdmodel.common.PDRectangle(30f, 50f, 500f, 650f); document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText(); content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 36f)
                    content.newLineAtOffset(80f, 600f); content.showText("DOCUMENT"); content.endText()
                }
                val layer = PdfTextLayer.page(document, document.numberOfPages - 1)
                assertTrue(layer.path("words").size() == 1, "Missing word at rotation $rotation: $layer")
                val word = OcrTextSelection(layer).words.single()
                val image = org.apache.pdfbox.rendering.PDFRenderer(document).renderImage(document.numberOfPages - 1, 1f)
                try {
                    val x1 = (word.left * image.width).toInt().coerceIn(0, image.width - 1); val x2 = (word.right * image.width).toInt().coerceIn(x1 + 1, image.width)
                    val y1 = (word.top * image.height).toInt().coerceIn(0, image.height - 1); val y2 = (word.bottom * image.height).toInt().coerceIn(y1 + 1, image.height)
                    assertTrue((x1 until x2).any { x -> (y1 until y2).any { y -> (image.getRGB(x, y) and 0xffffff) < 0x808080 } }, "No text in selected region at rotation $rotation: $word")
                } finally { image.flush() }
            }
        }
    }
    @Test fun `extracts text with page coordinates and keeps pages separate`() {
        PDDocument().use { document ->
            repeat(2) { index ->
                val page = PDPage(); document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText(); content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                    content.newLineAtOffset(60f, 700f); content.showText(if (index == 0) "FIRST DOCUMENT" else "SECOND PAGE"); content.endText()
                }
            }
            val first = OcrTextSelection(PdfTextLayer.page(document, 0))
            assertEquals("FIRST DOCUMENT", first.text(0, first.words.lastIndex))
            assertTrue(first.words.all { it.left >= 0 && it.right <= 1 && it.top >= 0 && it.bottom <= 1 })
            assertEquals(0, first.hit((first.words[0].left + first.words[0].right) / 2, (first.words[0].top + first.words[0].bottom) / 2))
            val second = OcrTextSelection(PdfTextLayer.page(document, 1))
            assertEquals("SECOND PAGE", second.text(0, second.words.lastIndex))
        }
    }
}
