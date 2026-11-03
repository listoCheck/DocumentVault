package ru.documentvault.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImageOrientationTest {
    @Test fun `all exif orientations preserve the expected pixel arrangement`() {
        val expected = listOf(
            listOf(1, 2, 3, 4, 5, 6), listOf(3, 2, 1, 6, 5, 4), listOf(6, 5, 4, 3, 2, 1), listOf(4, 5, 6, 1, 2, 3),
            listOf(1, 4, 2, 5, 3, 6), listOf(4, 1, 5, 2, 6, 3), listOf(6, 3, 5, 2, 4, 1), listOf(3, 6, 2, 5, 1, 4)
        )
        val source = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until 6) source.setRGB(i % 3, i / 3, (i + 1) * 0x010101)
        try {
            for (orientation in 1..8) {
                val result = LocalOcr.orientImage(source, orientation)
                try {
                    assertEquals(if (orientation <= 4) 3 else 2, result.width)
                    assertEquals(if (orientation <= 4) 2 else 3, result.height)
                    val pixels = (0 until 6).map { result.getRGB(it % result.width, it / result.width) and 0xff }
                    assertEquals(expected[orientation - 1], pixels, "orientation $orientation")
                } finally { if (result !== source) result.flush() }
            }
        } finally { source.flush() }
    }

    @Test fun `reads orientation from a jpeg and defaults for absent metadata`() {
        val image = BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        try { ImageIO.write(image, "jpeg", output) } finally { image.flush() }
        val jpeg = output.toByteArray()
        val exif = byteArrayOf(
            0xff.toByte(), 0xe1.toByte(), 0, 34, 0x45, 0x78, 0x69, 0x66, 0, 0,
            0x49, 0x49, 42, 0, 8, 0, 0, 0, 1, 0,
            0x12, 1, 3, 0, 1, 0, 0, 0, 6, 0, 0, 0, 0, 0, 0, 0
        )
        assertEquals(1, LocalOcr.readOrientation(jpeg))
        assertEquals(6, LocalOcr.readOrientation(jpeg.copyOfRange(0, 2) + exif + jpeg.copyOfRange(2, jpeg.size)))
        assertEquals(1, LocalOcr.readOrientation(byteArrayOf(0, 1, 2)))
    }

    @Test fun `large image is subsampled before OCR`() {
        val image = BufferedImage(6001, 2, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        try { ImageIO.write(image, "png", output) } finally { image.flush() }
        val decoded = LocalOcr.decodeImage(output.toByteArray())
        try { assertEquals(1501, decoded.width); assertEquals(1, decoded.height) } finally { decoded.flush() }
    }
    @Test fun `pdf rendering scale caps dimensions and rejects invalid page size`() {
        assertEquals(150f / 72f, LocalOcr.pdfScale(595f, 842f))
        assertTrue(LocalOcr.pdfScale(100_000f, 20_000f) * 100_000f <= 3000f)
        assertFailsWith<IllegalArgumentException> { LocalOcr.pdfScale(0f, 10f) }
        assertFailsWith<IllegalArgumentException> { LocalOcr.pdfScale(Float.POSITIVE_INFINITY, 10f) }
        PDDocument().use { pdf ->
            val page = PDPage(PDRectangle(10_000f, 1_000f)); pdf.addPage(page)
            val bitmap = PDFRenderer(pdf).renderImage(0, LocalOcr.pdfScale(page.cropBox.width, page.cropBox.height), ImageType.RGB)
            try { assertTrue(bitmap.width <= 3000); assertTrue(bitmap.height <= 3000) } finally { bitmap.flush() }
        }
    }
}
