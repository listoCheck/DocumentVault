package ru.documentvault.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class LocalOcrTest {
    @Test fun `reads image and scanned pdf locally`() {
        val image = BufferedImage(1300, 260, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE; graphics.fillRect(0, 0, image.width, image.height)
        graphics.color = Color.BLACK; graphics.font = Font("Arial", Font.BOLD, 72)
        graphics.drawString("DOCUMENT 2030", 50, 140); graphics.dispose()
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        assertTrue(LocalOcr.recognize("image.png", png).contains("DOCUMENT", ignoreCase = true))
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
}
