package ru.documentvault.experiments

import ru.documentvault.desktop.LocalOcr
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: ocrImages <manifest.tsv>" }
    val manifest = Path.of(args[0]).toAbsolutePath().normalize()
    var totals = OcrCounts(0, 0, 0, 0); var rows = 0; var totalNanos = 0L
    Files.readAllLines(manifest, Charsets.UTF_8).forEachIndexed { index, line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        val parts = line.split('\t')
        require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Expected image path<TAB>reference text at row ${index + 1}" }
        val image = manifest.parent.resolve(parts[0]).normalize()
        require(Files.isRegularFile(image)) { "Image not found at row ${index + 1}" }
        require(Files.size(image) <= 15L * 1024 * 1024) { "Image exceeds 15 MiB at row ${index + 1}" }
        val started = System.nanoTime()
        val result = LocalOcr.recognize(image.fileName.toString(), Files.readAllBytes(image)).trimEnd('\r', '\n')
        totalNanos += System.nanoTime() - started
        totals += OcrMetrics.compare(parts[1], result)
        rows++
    }
    require(rows > 0) { "Empty dataset" }
    println("Samples: $rows; desktop OCR rus+eng; NFC normalization; final OCR line breaks removed; case and punctuation preserved.")
    println(String.format(Locale.ROOT, "Character errors: %d / %d; CER=%.4f", totals.characterErrors, totals.referenceCharacters, totals.characterErrors.toDouble() / totals.referenceCharacters))
    println(String.format(Locale.ROOT, "Word errors: %d / %d; WER=%.4f", totals.wordErrors, totals.referenceWords, totals.wordErrors.toDouble() / totals.referenceWords))
    println(String.format(Locale.ROOT, "OCR elapsed total: %.3f s; mean: %.3f s/image (includes model setup and file read)", totalNanos / 1e9, totalNanos / 1e9 / rows))
}
