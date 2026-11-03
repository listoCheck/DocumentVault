package ru.documentvault.experiments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OcrMetricsTest {
    @Test fun `counts substitutions insertions and word differences`() {
        assertEquals(OcrCounts(7, 5, 2, 2), OcrMetrics.compare("ab cd", "ax cd extra"))
    }
    @Test fun `normalizes composed unicode and handles supplementary characters`() {
        assertEquals(OcrCounts(0, 2, 0, 1), OcrMetrics.compare("е\u0308😀", "ё😀"))
        assertEquals(OcrCounts(1, 2, 1, 1), OcrMetrics.compare("я😀", "я"))
    }
    @Test fun `empty recognized text is a deletion and empty reference is rejected`() {
        assertEquals(OcrCounts(3, 3, 1, 1), OcrMetrics.compare("кот", ""))
        assertFailsWith<IllegalArgumentException> { OcrMetrics.compare(" ", "test") }
    }
}
