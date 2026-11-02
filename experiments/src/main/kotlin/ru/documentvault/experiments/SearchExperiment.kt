package ru.documentvault.experiments

import ru.documentvault.core.OcrSearch
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

private data class Counts(var tp: Int = 0, var fp: Int = 0, var fn: Int = 0, var tn: Int = 0) {
    fun add(expected: Boolean, actual: Boolean) {
        when { expected && actual -> tp++; !expected && actual -> fp++; expected -> fn++; else -> tn++ }
    }
    fun report(name: String) {
        val precision = if (tp + fp == 0) 0.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 0.0 else tp.toDouble() / (tp + fn)
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        println(String.format(Locale.ROOT, "%s: TP=%d FP=%d FN=%d TN=%d precision=%.3f recall=%.3f F1=%.3f", name, tp, fp, fn, tn, precision, recall, f1))
    }
}

fun main(args: Array<String>) {
    val dataset = Path.of(args.firstOrNull() ?: "data/search-synthetic.tsv")
    val baseline = Counts(); val fuzzy = Counts(); var rows = 0
    Files.readAllLines(dataset, Charsets.UTF_8).forEachIndexed { index, line ->
        if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
        val parts = line.split('\t')
        require(parts.size == 3 && parts[2] in setOf("0", "1")) { "Invalid row ${index + 1}" }
        val (document, query, label) = parts
        val expected = label == "1"
        val terms = Regex("[\\p{L}\\p{N}]+").findAll(document.lowercase(Locale.ROOT)).map { it.value }.toSet()
        val queries = Regex("[\\p{L}\\p{N}]+").findAll(query.lowercase(Locale.ROOT)).map { it.value }.toList()
        baseline.add(expected, queries.isNotEmpty() && queries.all { it in terms })
        fuzzy.add(expected, OcrSearch.matches(document, query))
        rows++
    }
    require(rows > 0) { "Empty dataset" }
    println("Synthetic samples: $rows. Labels are manually assigned; this is a pipeline check, not a representative study.")
    baseline.report("Exact token matching")
    fuzzy.report("OCR-tolerant matching")
}
