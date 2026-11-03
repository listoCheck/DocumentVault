package ru.documentvault.experiments

import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale

data class OcrCounts(val characterErrors: Long, val referenceCharacters: Long, val wordErrors: Long, val referenceWords: Long) {
    operator fun plus(other: OcrCounts) = OcrCounts(characterErrors + other.characterErrors, referenceCharacters + other.referenceCharacters, wordErrors + other.wordErrors, referenceWords + other.referenceWords)
}

object OcrMetrics {
    private fun <T> distance(left: List<T>, right: List<T>): Int {
        // Keep the shorter sequence in memory. The metric has unit insertion, deletion and substitution costs.
        if (left.size > right.size) return distance(right, left)
        var previous = IntArray(left.size + 1) { it }
        var current = IntArray(left.size + 1)
        for (i in right.indices) {
            current[0] = i + 1
            for (j in left.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (left[j] == right[i]) 0 else 1)
            val temp = previous; previous = current; current = temp
        }
        return previous[left.size]
    }

    fun compare(reference: String, recognized: String): OcrCounts {
        val expected = Normalizer.normalize(reference, Normalizer.Form.NFC)
        val actual = Normalizer.normalize(recognized, Normalizer.Form.NFC)
        require(expected.isNotBlank()) { "Reference text must not be empty" }
        val expectedCharacters = expected.codePoints().toArray().toList()
        val actualCharacters = actual.codePoints().toArray().toList()
        fun words(value: String) = value.trim().let { if (it.isEmpty()) emptyList() else it.split(Regex("\\s+")) }
        val expectedWords = words(expected); val actualWords = words(actual)
        return OcrCounts(distance(expectedCharacters, actualCharacters).toLong(), expectedCharacters.size.toLong(), distance(expectedWords, actualWords).toLong(), expectedWords.size.toLong())
    }
}

fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: ocrMetrics <dataset.tsv>" }
    var totals = OcrCounts(0, 0, 0, 0); var rows = 0
    Files.readAllLines(Path.of(args[0]), Charsets.UTF_8).forEachIndexed { index, line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        val parts = line.split('\t')
        require(parts.size == 2 && parts[0].isNotBlank()) { "Expected reference<TAB>recognized at row ${index + 1}" }
        totals += OcrMetrics.compare(parts[0], parts[1]); rows++
    }
    require(rows > 0) { "Empty dataset" }
    println("Samples: $rows; NFC normalization; case and punctuation preserved; words split by whitespace.")
    println(String.format(Locale.ROOT, "Character errors: %d / %d; CER=%.4f", totals.characterErrors, totals.referenceCharacters, totals.characterErrors.toDouble() / totals.referenceCharacters))
    println(String.format(Locale.ROOT, "Word errors: %d / %d; WER=%.4f", totals.wordErrors, totals.referenceWords, totals.wordErrors.toDouble() / totals.referenceWords))
}
