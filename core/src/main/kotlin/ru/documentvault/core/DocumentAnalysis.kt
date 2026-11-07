package ru.documentvault.core

import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

data class DateCandidate(val date: LocalDate, val excerpt: String, val likelyExpiry: Boolean)

object DocumentDates {
    private val numeric = Regex("(?iu)(?<!\\d)(?:(\\d{4})-(\\d{1,2})-(\\d{1,2})|(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})|(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4}))(?!\\d)")
    private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val expiry = Regex("(?iu)(действителен|действительна|действительно|действует|срок действия|годен|истекает|expiry|expiration|valid until|expires)")

    /** Extract date candidates with their surrounding text. */
    fun find(text: String): List<DateCandidate> = numeric.findAll(text).mapNotNull { match ->
        try {
            val iso = match.groupValues[1].isNotEmpty()
            val textual = match.groupValues[7].isNotEmpty()
            val month = if (textual) months.indexOf(match.groupValues[8].lowercase(Locale.ROOT)) + 1 else match.groupValues[if (iso) 2 else 5].toInt()
            val date = LocalDate.of(match.groupValues[if (textual) 9 else if (iso) 1 else 6].toInt(), month, match.groupValues[if (textual) 7 else if (iso) 3 else 4].toInt())
            val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', match.range.last + 1).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            DateCandidate(date, line.take(200), expiry.containsMatchIn(line))
        } catch (_: DateTimeException) { null }
    }.distinctBy { it.date to it.excerpt }.toList()

    fun automaticExpiry(text: String): LocalDate? {
        val label = Regex("(?iu)(?:действителен|действительна|действительно|действует|срок\\s+действия|годен|годна|истекает|дата\\s+окончания|expiry|expiration|valid\\s+(?:until|through|to)|expires)")
        val until = Regex("(?iu)(?:(?<![\\p{L}])до(?![\\p{L}])|(?<![\\p{L}])по(?![\\p{L}])|until|through|expires?\\s+on)")
        val cutoff = Regex("(?iu)(?:выдан|выдана|дата\\s+рождения|issued|birth)")
        val candidates = mutableSetOf<LocalDate>()
        for (line in text.lineSequence()) for (match in label.findAll(line)) {
            var tail = line.substring(match.range.last + 1).take(120).split(cutoff, limit = 2).first()
            val endMarker = until.find(tail)
            if (endMarker != null) tail = tail.substring(endMarker.range.last + 1)
            else if (Regex("(?iu)(действу|действител|годен|годна)").containsMatchIn(match.value)) continue
            val dates = find(tail).map { it.date }.distinct()
            if (dates.size > 1) return null
            if (dates.size == 1) candidates.add(dates.first())
        }
        return candidates.singleOrNull()
    }

    fun dueWithin(date: LocalDate, today: LocalDate, days: Long = 30): Boolean {
        require(days >= 0)
        return !date.isBefore(today) && !date.isAfter(today.plusDays(days))
    }
}

object OcrSearch {
    private val words = Regex("[\\p{L}\\p{N}]+")
    private val similar = mapOf('a' to 'а', 'b' to 'в', 'c' to 'с', 'e' to 'е', 'h' to 'н', 'k' to 'к', 'm' to 'м', 'o' to 'о', 'p' to 'р', 't' to 'т', 'x' to 'х', 'y' to 'у', '0' to 'о', '3' to 'з', '1' to 'и')
    fun normalize(text: String): List<String> = words.findAll(Normalizer.normalize(text.lowercase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFKC))
        .map { match -> match.value.map { similar[it] ?: it }.joinToString("") }.toList()

    /** All query terms must match one text token. Small words require an exact match. */
    fun matches(text: String, query: String): Boolean {
        val terms = normalize(query)
        if (terms.isEmpty()) return false
        val tokens = normalize(text).distinct()
        return terms.all { term -> tokens.any { token ->
            token == term || (term.length >= 5 && token.length >= 5 && distanceAtMostOne(term, token))
        } }
    }

    private fun distanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var errors = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++errors > 1) return false
            when { a.length > b.length -> i++; b.length > a.length -> j++; else -> { i++; j++ } }
        }
        return errors + (a.length - i) + (b.length - j) <= 1
    }
}
