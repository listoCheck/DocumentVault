package ru.documentvault.desktop

import net.sourceforge.tess4j.Tesseract
import com.drew.imaging.ImageMetadataReader
import com.drew.imaging.ImageProcessingException
import com.drew.metadata.exif.ExifIFD0Directory
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.IOException
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream

object LocalOcr {
    data class Result(val text: String, val layout: com.fasterxml.jackson.databind.node.ObjectNode?)
    fun recognize(name: String, data: ByteArray): String = recognizeDetailed(name, data).text
    fun recognizeDetailed(name: String, data: ByteArray): Result {
        val tessdata = (System.getenv("VAULT_TESSDATA")?.let { Path.of(it) }
            ?: listOf(Path.of("tessdata"), Path.of("..", "tessdata")).firstOrNull { Files.exists(it.resolve("rus.traineddata")) })
            ?: error("Не найдены языковые модели tessdata. Укажите VAULT_TESSDATA.")
        require(Files.exists(tessdata.resolve("eng.traineddata")) && Files.exists(tessdata.resolve("rus.traineddata"))) { "Нужны модели rus.traineddata и eng.traineddata." }
        val tesseract = Tesseract().apply { setDatapath(tessdata.toAbsolutePath().normalize().toString()); setLanguage("rus+eng") }
        if (name.endsWith(".pdf", ignoreCase = true)) {
            Loader.loadPDF(data).use { pdf ->
                require(pdf.numberOfPages in 1..20) { "Поддерживаются PDF не более 20 страниц." }
                val renderer = PDFRenderer(pdf)
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                val text = (0 until pdf.numberOfPages).joinToString("\n") { page ->
                    stripper.startPage = page + 1; stripper.endPage = page + 1
                    val embedded = stripper.getText(pdf).trim()
                    val resources = pdf.getPage(page).resources
                    // XObjects may contain scanned content, including images inside forms.
                    val hasExternalContent = resources != null && resources.xObjectNames.iterator().hasNext()
                    if (embedded.any(Char::isLetterOrDigit) && !hasExternalContent) embedded else {
                        val box = pdf.getPage(page).cropBox
                        val image = renderer.renderImage(page, pdfScale(box.width, box.height), ImageType.RGB)
                        try { tesseract.doOCR(image) } finally { image.flush() }
                    }
                }
                return Result(text, null)
            }
        }
        val image = decodeImage(data)
        var oriented: BufferedImage? = null
        return try {
            oriented = orientImage(image, readOrientation(data))
            val json = com.fasterxml.jackson.databind.ObjectMapper(); val words = json.createArrayNode()
            val detected = mutableListOf<DetectedWord>()
            for (mode in listOf(3, 11)) {
                tesseract.setPageSegMode(mode)
                for (word in tesseract.getWords(oriented, net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel.RIL_WORD)) {
                    val value = word.text?.trim().orEmpty(); val box = word.boundingBox ?: continue
                    if (value.isEmpty() || box.width <= 0 || box.height <= 0 || box.x < 0 || box.y < 0 || box.x + box.width > oriented.width || box.y + box.height > oriented.height) continue
                    val candidate = DetectedWord(value, box, word.confidence)
                    val duplicate = detected.indexOfFirst { overlaps(it.box, box) }
                    if (duplicate < 0 && detected.size < 3000) detected.add(candidate)
                    else if (duplicate >= 0 && word.confidence > detected[duplicate].confidence + 5) detected[duplicate] = candidate
                }
            }
            val lines = mutableListOf<MutableList<DetectedWord>>()
            for (word in detected.sortedBy { it.box.centerY }) {
                val line = lines.firstOrNull { group -> group.any { item -> kotlin.math.abs(item.box.centerY - word.box.centerY) <= maxOf(item.box.height, word.box.height) * 0.55 } }
                if (line == null) lines.add(mutableListOf(word)) else line.add(word)
            }
            val text = lines.joinToString("\n") { line ->
                line.sortBy { it.box.x }
                line.mapNotNull { word ->
                    val textWord = word.value.takeIf { word.confidence >= 15 || it.any(Char::isDigit) }.orEmpty()
                    val box = word.box
                    words.add(json.createArrayNode().add(textWord).add(box.x.toDouble() / oriented.width).add(box.y.toDouble() / oriented.height)
                        .add((box.x + box.width).toDouble() / oriented.width).add((box.y + box.height).toDouble() / oriented.height))
                    textWord.takeIf(String::isNotEmpty)
                }.joinToString(" ")
            }.trim()
            val layout = json.createObjectNode().put("version", 1); layout.set<com.fasterxml.jackson.databind.JsonNode>("words", words)
            Result(text, layout)
        } finally { if (oriented !== image) oriented?.flush(); image.flush() }
    }

    private data class DetectedWord(val value: String, val box: java.awt.Rectangle, val confidence: Float)
    private fun overlaps(a: java.awt.Rectangle, b: java.awt.Rectangle): Boolean {
        val intersection = a.intersection(b)
        if (intersection.isEmpty) return false
        val area = intersection.width.toDouble() * intersection.height
        return area / minOf(a.width.toDouble() * a.height, b.width.toDouble() * b.height) >= 0.6
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
