package ru.documentvault.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalOcrTest {
    @Test fun `blue stamp lettering is not added to recognized text`() {
        val image = BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { g ->
            g.color = Color.WHITE; g.fillRect(0, 0, image.width, image.height); g.font = Font("Arial", Font.BOLD, 72)
            g.color = Color.BLACK; g.drawString("DOCUMENT 2030", 60, 180)
            g.color = Color(40, 65, 150); g.drawString("FAKESTAMP", 60, 430); g.dispose()
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray(); image.flush()
        val result = LocalOcr.recognize("blue.png", bytes)
        assertTrue(result.contains("DOCUMENT", ignoreCase = true), result)
        assertTrue(!result.contains("FAKESTAMP", ignoreCase = true), result)
    }
    @Test fun `reads multiple lines of small text on a dim photographed page`() {
        val image = BufferedImage(900, 1250, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, Color(125, 125, 125), 900f, 1250f, Color(205, 205, 205))
        g.fillRect(0, 0, image.width, image.height); g.color = Color(20, 20, 20); g.font = Font("Arial", Font.PLAIN, 25)
        g.drawString("СПРАВКА", 85, 130); g.drawString("ДОКУМЕНТ ДЕЙСТВУЕТ ДО 31.12.2030", 85, 210)
        g.drawString("ПРОВЕРКА ТЕКСТА И ДАННЫХ", 85, 275); g.drawString("НОМЕР ДОКУМЕНТА 123456", 85, 340)
        g.drawString("ДАТА 15.10.2025", 85, 405); g.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray(); image.flush()
        val result = LocalOcr.recognizeDetailed("dim.png", bytes)
        for (expected in listOf("СПРАВКА", "ДОКУМЕНТ", "ДЕЙСТВУЕТ", "ПРОВЕРКА", "ТЕКСТА", "НОМЕР", "ДАТА")) {
            assertTrue(result.text.contains(expected, ignoreCase = true), "Missing $expected: ${result.text}")
        }
        assertTrue(result.layout!!.path("words").any { it[0].asText().contains("ДАТА", ignoreCase = true) && it[2].asDouble() > .25 })
    }
    @Test fun `enhances a dark low contrast page while preserving its dimensions`() {
        val image = BufferedImage(1200, 360, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().also { g ->
            g.color = Color(105, 105, 105); g.fillRect(0, 0, image.width, image.height)
            g.color = Color(45, 45, 45); g.font = Font("Arial", Font.BOLD, 80); g.drawString("DOCUMENT 2030", 40, 190); g.dispose()
        }
        val enhanced = LocalOcr.enhanceContrast(image)
        try {
            assertEquals(image.width, enhanced.width); assertEquals(image.height, enhanced.height)
            assertTrue((enhanced.getRGB(5, 5) and 255) > (image.getRGB(5, 5) and 255))
            val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
            assertTrue(LocalOcr.recognize("dark.png", bytes).contains("DOCUMENT", ignoreCase = true))
        } finally { enhanced.flush(); image.flush() }
    }
    @Test fun `extracts digital text and recognizes a scanned page in one pdf`() {
        val image = BufferedImage(1300, 260, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE; graphics.fillRect(0, 0, image.width, image.height)
        graphics.color = Color.BLACK; graphics.font = Font("Arial", Font.BOLD, 72)
        graphics.drawString("SCANNED 2030", 50, 140); graphics.dispose()
        val bytes = ByteArrayOutputStream()
        PDDocument().use { document ->
            val digital = PDPage(); document.addPage(digital)
            PDPageContentStream(document, digital).use { content ->
                content.beginText(); content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 8f)
                content.newLineAtOffset(40f, 700f); content.showText("Exact reference: AB-12938/2029"); content.endText()
            }
            val scan = PDPage(); document.addPage(scan)
            PDPageContentStream(document, scan).use { it.drawImage(LosslessFactory.createFromImage(document, image), 10f, 500f, 500f, 100f) }
            document.save(bytes)
        }
        image.flush()
        val result = LocalOcr.recognize("mixed.pdf", bytes.toByteArray())
        assertTrue(result.contains("Exact reference: AB-12938/2029"), result)
        assertTrue(result.contains("SCANNED 2030", ignoreCase = true), result)
    }
    @Test fun `reads image and scanned pdf locally`() {
        val image = BufferedImage(1300, 260, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE; graphics.fillRect(0, 0, image.width, image.height)
        graphics.color = Color.BLACK; graphics.font = Font("Arial", Font.BOLD, 72)
        graphics.drawString("DOCUMENT 2030", 50, 140); graphics.dispose()
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        val result = LocalOcr.recognizeDetailed("image.png", png)
        assertTrue(result.text.contains("DOCUMENT", ignoreCase = true))
        assertTrue(result.layout != null && result.layout.path("words").size() > 0)
        val pdfBytes = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            PDPageContentStream(doc, page).use { content -> content.drawImage(LosslessFactory.createFromImage(doc, image), 10f, 500f, 500f, 100f) }
            doc.save(pdfBytes)
        }
        assertTrue(LocalOcr.recognize("scan.pdf", pdfBytes.toByteArray()).contains("DOCUMENT", ignoreCase = true))
        val g = image.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, image.width, image.height)
        g.color = Color.BLACK; g.font = Font("Arial", Font.BOLD, 72)
        g.drawString("ДОКУМЕНТ", 50, 140); g.dispose()
        val russian = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        assertTrue(LocalOcr.recognize("russian.png", russian).contains("ДОКУМЕНТ", ignoreCase = true))
        image.flush()
    }
    @Test fun `reads sparse fields across the whole photographed form`() {
        val image = BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); g.color = Color(230, 228, 220); g.fillRect(0, 0, 1600, 1200)
        g.color = Color.BLACK; g.font = Font("Arial", Font.BOLD, 65)
        g.drawString("DOCUMENT 2030", 70, 150); g.drawString("ВОПРОС", 70, 570); g.drawString("МЕТОДИКА", 70, 1070); g.dispose()
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray(); image.flush()
        val result = LocalOcr.recognizeDetailed("form.png", bytes)
        assertTrue(result.text.contains("DOCUMENT", ignoreCase = true), result.text)
        assertTrue(result.text.contains("ВОПРОС", ignoreCase = true), result.text)
        assertTrue(result.text.contains("МЕТОДИКА", ignoreCase = true), result.text)
        assertTrue(result.layout!!.path("words").any { it[0].asText().contains("МЕТОДИКА", ignoreCase = true) && it[2].asDouble() > 0.7 })
    }
}
