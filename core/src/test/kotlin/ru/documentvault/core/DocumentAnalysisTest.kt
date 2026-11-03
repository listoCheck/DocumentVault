package ru.documentvault.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentAnalysisTest {
    @Test fun `extracts valid dates and marks expiry context`() {
        val result = DocumentDates.find("Выдан 01.02.2020\nДействителен до 31.12.2030\nОшибка 31.02.2030")
        assertEquals(2, result.size)
        assertEquals(LocalDate.of(2030, 12, 31), result[1].date)
        assertFalse(result[0].likelyExpiry)
        assertTrue(result[1].likelyExpiry)
    }
    @Test fun `search tolerates common OCR substitutions and one edit`() {
        assertTrue(OcrSearch.matches("Иванов Александр", "1ванов"))
        assertTrue(OcrSearch.matches("договор страхования", "договoр"))
        assertTrue(OcrSearch.matches("Свидетельство", "свидетeльствo"))
        assertTrue(OcrSearch.matches("страхование", "страхованне"))
        assertFalse(OcrSearch.matches("договор", "паспорт"))
        assertFalse(OcrSearch.matches("договор", ""))
    }
    @Test fun `extracts ISO dates alongside local formats and ignores invalid dates`() {
        val result = DocumentDates.find("Истекает 2030-12-31\nВыдан 01.02.2020\nДействителен до 29 ФЕВРАЛЯ 2024\n2023-02-29\n31 апреля 2030")
        assertEquals(listOf(LocalDate.of(2030, 12, 31), LocalDate.of(2020, 2, 1), LocalDate.of(2024, 2, 29)), result.map { it.date })
        assertTrue(result.first().likelyExpiry)
    }
    @Test fun `expiry window includes today and the thirtieth day`() {
        val today = LocalDate.of(2030, 1, 1)
        assertTrue(DocumentDates.dueWithin(today, today))
        assertTrue(DocumentDates.dueWithin(today.plusDays(30), today))
        assertFalse(DocumentDates.dueWithin(today.plusDays(31), today))
        assertFalse(DocumentDates.dueWithin(today.minusDays(1), today))
    }
}
