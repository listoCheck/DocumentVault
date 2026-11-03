package ru.documentvault.desktop

import net.sourceforge.tess4j.Tesseract
import com.drew.imaging.ImageMetadataReader
import com.drew.imaging.ImageProcessingException
import com.drew.metadata.exif.ExifIFD0Directory
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayInputStream
import java.io.IOException
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

object LocalOcr {
    fun recognize(name: String, data: ByteArray): String {
        val tessdata = (System.getenv("VAULT_TESSDATA")?.let { Path.of(it) }
            ?: listOf(Path.of("tessdata"), Path.of("..", "tessdata")).firstOrNull { Files.exists(it.resolve("rus.traineddata")) })
            ?: error("Не найдены языковые модели tessdata. Укажите VAULT_TESSDATA.")
        require(Files.exists(tessdata.resolve("eng.traineddata")) && Files.exists(tessdata.resolve("rus.traineddata"))) { "Нужны модели rus.traineddata и eng.traineddata." }
        val tesseract = Tesseract().apply { setDatapath(tessdata.toAbsolutePath().normalize().toString()); setLanguage("rus+eng") }
        if (name.endsWith(".pdf", ignoreCase = true)) {
            Loader.loadPDF(data).use { pdf ->
                require(pdf.numberOfPages in 1..20) { "Поддерживаются PDF не более 20 страниц." }
                val renderer = PDFRenderer(pdf)
                return (0 until pdf.numberOfPages).joinToString("\n") { page ->
                    val box = pdf.getPage(page).cropBox
                    val image = renderer.renderImage(page, pdfScale(box.width, box.height), ImageType.RGB)
                    try { tesseract.doOCR(image) } finally { image.flush() }
                }
            }
        }
        val image = decodeImage(data)
        var oriented: BufferedImage? = null
        return try {
            oriented = orientImage(image, readOrientation(data))
            tesseract.doOCR(oriented)
        } finally { if (oriented !== image) oriented?.flush(); image.flush() }
    }

    internal fun pdfScale(width: Float, height: Float): Float {
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0) { "Некорректный размер страницы PDF." }
        return minOf(150f / 72f, 3000f / maxOf(width, height))
    }

    internal fun decodeImage(data: ByteArray): BufferedImage {
        val input = MemoryCacheImageInputStream(ByteArrayInputStream(data))
        input.use {
            val readers = ImageIO.getImageReaders(input)
            require(readers.hasNext()) { "Неподдерживаемый формат изображения." }
            val reader = readers.next()
            try {
                reader.setInput(input, true, true)
                val width = reader.getWidth(0); val height = reader.getHeight(0)
                require(width > 0 && height > 0) { "Некорректный размер изображения." }
                var sample = 1
                val longest = maxOf(width, height).toLong()
                while ((longest + sample - 1) / sample > 3000) sample *= 2
                val params = reader.defaultReadParam
                params.setSourceSubsampling(sample, sample, 0, 0)
                return reader.read(0, params) ?: error("Не удалось открыть изображение.")
            } finally { reader.dispose() }
        }
    }

    internal fun readOrientation(data: ByteArray): Int = try {
        ImageMetadataReader.readMetadata(ByteArrayInputStream(data))
            .getFirstDirectoryOfType(ExifIFD0Directory::class.java)?.getInteger(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
    } catch (_: ImageProcessingException) { 1 } catch (_: IOException) { 1 }

    internal fun orientImage(image: BufferedImage, orientation: Int): BufferedImage {
        val width = image.width.toDouble(); val height = image.height.toDouble()
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, width, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, width, height)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, height)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, height, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, height, width)
            8 -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, width)
            else -> return image
        }
        val result = if (orientation in 5..8) BufferedImage(image.height, image.width, BufferedImage.TYPE_INT_ARGB)
            else BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = result.createGraphics()
        try { graphics.drawImage(image, transform, null) } finally { graphics.dispose() }
        return result
    }
}
