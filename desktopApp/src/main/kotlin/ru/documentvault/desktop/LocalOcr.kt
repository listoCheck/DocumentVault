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
    private fun createTesseract(): Tesseract {
        val tessdata = (System.getenv("VAULT_TESSDATA")?.let { Path.of(it) }
            ?: listOf(Path.of("tessdata"), Path.of("..", "tessdata")).firstOrNull { Files.exists(it.resolve("rus.traineddata")) })
            ?: error("Не найдены языковые модели tessdata. Укажите VAULT_TESSDATA.")
        require(Files.exists(tessdata.resolve("eng.traineddata")) && Files.exists(tessdata.resolve("rus.traineddata"))) { "Нужны модели rus.traineddata и eng.traineddata." }
        return Tesseract().apply { setDatapath(tessdata.toAbsolutePath().normalize().toString()); setLanguage("rus+eng") }
    }
    fun recognizeDetailed(name: String, data: ByteArray): Result {
        if (name.endsWith(".pdf", ignoreCase = true)) {
            Loader.loadPDF(data).use { pdf ->
                require(pdf.numberOfPages in 1..20) { "Поддерживаются PDF не более 20 страниц." }
                val renderer = PDFRenderer(pdf)
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                var tesseract: Tesseract? = null
                val text = (0 until pdf.numberOfPages).joinToString("\n") { page ->
                    stripper.startPage = page + 1; stripper.endPage = page + 1
                    val embedded = stripper.getText(pdf).trim()
                    val resources = pdf.getPage(page).resources
                    // XObjects may contain scanned content, including images inside forms.
                    val hasExternalContent = resources != null && resources.xObjectNames.iterator().hasNext()
                    if (embedded.any(Char::isLetterOrDigit) && !hasExternalContent) embedded else {
                        val box = pdf.getPage(page).cropBox
                        val image = renderer.renderImage(page, pdfScale(box.width, box.height), ImageType.RGB)
                        try { (tesseract ?: createTesseract().also { tesseract = it }).doOCR(image) } finally { image.flush() }
                    }
                }
                return Result(text, null)
            }
        }
        val tesseract = createTesseract()
        val image = decodeImage(data)
        var oriented: BufferedImage? = null
        return try {
            oriented = orientImage(image, readOrientation(data))
            val json = com.fasterxml.jackson.databind.ObjectMapper(); val words = json.createArrayNode()
            val detected = mutableListOf<DetectedWord>()
            val enhanced = enhanceContrast(oriented)
            val enlarged = enlargeForOcr(enhanced)
            val normalized = normalizeLighting(oriented)
            val enlargedNormalized = enlargeForOcr(normalized)
            try { for (scan in listOf(oriented, enlarged, enlargedNormalized)) for (mode in listOf(3, 11)) {
                tesseract.setPageSegMode(mode)
                for (word in tesseract.getWords(scan, net.sourceforge.tess4j.ITessAPI.TessPageIteratorLevel.RIL_WORD)) {
                    val value = word.text?.trim().orEmpty(); val raw = word.boundingBox ?: continue
                    val box = java.awt.Rectangle(
                        (raw.x.toDouble() * oriented.width / scan.width).toInt(), (raw.y.toDouble() * oriented.height / scan.height).toInt(),
                        kotlin.math.ceil(raw.width.toDouble() * oriented.width / scan.width).toInt(), kotlin.math.ceil(raw.height.toDouble() * oriented.height / scan.height).toInt())
                    if (value.isEmpty() || box.width <= 0 || box.height <= 0 || box.x < 0 || box.y < 0 || box.x + box.width > oriented.width || box.y + box.height > oriented.height) continue
                    if (ColoredInk.mostlyBlue(oriented, box)) continue
                    val candidate = DetectedWord(value, box, word.confidence)
                    mergeCandidate(detected, candidate)
                }
            } } finally { if (enlarged !== enhanced) enlarged.flush(); enhanced.flush(); if (enlargedNormalized !== normalized) enlargedNormalized.flush(); normalized.flush() }
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
    private fun mergeCandidate(found: MutableList<DetectedWord>, candidate: DetectedWord) {
        if (!candidate.value.any(Char::isLetterOrDigit) || (candidate.confidence < 15 && candidate.value.none(Char::isDigit))) return
        val duplicate = found.indexOfFirst { overlaps(it.box, candidate.box) }
        if (duplicate < 0) { if (found.size < 3000) found.add(candidate); return }
        val old = found[duplicate]
        val oldArea = old.box.width.toDouble() * old.box.height; val newArea = candidate.box.width.toDouble() * candidate.box.height
        val areaRatio = minOf(oldArea, newArea) / maxOf(oldArea, newArea)
        if (areaRatio < 0.45) {
            // A high-confidence speck must not replace a complete word.
            if (newArea > oldArea && candidate.confidence >= 20 && candidate.value.length > old.value.length) found[duplicate] = candidate
        } else if (candidate.confidence > old.confidence + 5) found[duplicate] = candidate
    }
    internal fun enlargeForOcr(source: BufferedImage): BufferedImage {
        val scale = minOf(2.0, 2400.0 / maxOf(source.width, source.height))
        if (scale < 1.1) return source
        val result = BufferedImage((source.width * scale).toInt(), (source.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = result.createGraphics()
        try { g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC); g.drawImage(source, 0, 0, result.width, result.height, null) } finally { g.dispose() }
        return result
    }
    internal fun normalizeLighting(source: BufferedImage): BufferedImage {
        val w = source.width; val h = source.height; val pixels = source.getRGB(0, 0, w, h, null, 0, w)
        val gray = IntArray(pixels.size); val horizontal = IntArray(pixels.size)
        for (i in pixels.indices) { val p = pixels[i]; gray[i] = ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000 }
        val radius = (maxOf(w, h) / 30).coerceIn(15, 75)
        for (y in 0 until h) {
            val offset = y * w; var sum = 0
            for (x in 0..minOf(radius, w - 1)) sum += gray[offset + x]
            for (x in 0 until w) {
                if (x > 0) { val add = x + radius; val remove = x - radius - 1; if (add < w) sum += gray[offset + add]; if (remove >= 0) sum -= gray[offset + remove] }
                horizontal[offset + x] = sum / (minOf(w - 1, x + radius) - maxOf(0, x - radius) + 1)
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (y in 0..minOf(radius, h - 1)) sum += horizontal[y * w + x]
            for (y in 0 until h) {
                if (y > 0) { val add = y + radius; val remove = y - radius - 1; if (add < h) sum += horizontal[add * w + x]; if (remove >= 0) sum -= horizontal[remove * w + x] }
                val average = sum / (minOf(h - 1, y + radius) - maxOf(0, y - radius) + 1)
                val value = (220 + (gray[y * w + x] - average) * 3).coerceIn(0, 255)
                pixels[y * w + x] = -0x1000000 or (value shl 16) or (value shl 8) or value
            }
        }
        gray.fill(0); horizontal.fill(0)
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, w, h, pixels, 0, w); pixels.fill(0) }
    }
    internal fun enhanceContrast(source: BufferedImage): BufferedImage {
        val width = source.width; val height = source.height
        val pixels = source.getRGB(0, 0, width, height, null, 0, width)
        val histogram = IntArray(256)
        for (pixel in pixels) { val gray = ((pixel shr 16 and 255) * 299 + (pixel shr 8 and 255) * 587 + (pixel and 255) * 114) / 1000; histogram[gray]++ }
        fun percentile(target: Int): Int { var total = 0; for (i in histogram.indices) { total += histogram[i]; if (total >= target) return i }; return 255 }
        val low = percentile((pixels.size / 100).coerceAtLeast(1)); val high = percentile((pixels.size * 99 / 100).coerceAtLeast(1))
        val span = (high - low).coerceAtLeast(1)
        for (i in pixels.indices) {
            val pixel = pixels[i]; val gray = ((pixel shr 16 and 255) * 299 + (pixel shr 8 and 255) * 587 + (pixel and 255) * 114) / 1000
            val value = if (span < 25) gray else ((gray - low) * 255 / span).coerceIn(0, 255)
            pixels[i] = -0x1000000 or (value shl 16) or (value shl 8) or value
        }
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, width, height, pixels, 0, width); pixels.fill(0) }
    }
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
