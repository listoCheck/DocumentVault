package ru.documentvault.desktop

import net.sourceforge.tess4j.Tesseract
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

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
                    val image = renderer.renderImageWithDPI(page, 150f, ImageType.RGB)
                    try { tesseract.doOCR(image) } finally { image.flush() }
                }
            }
        }
        val image = ImageIO.read(ByteArrayInputStream(data)) ?: error("Неподдерживаемый формат изображения.")
        return try { tesseract.doOCR(image) } finally { image.flush() }
    }
}
