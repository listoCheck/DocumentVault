package ru.documentvault.android;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

public class DateSuggestionsTest {
    @Test public void extractsOnlyValidDates() {
        assertEquals(java.util.Arrays.asList(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31)), DateSuggestions.find("Выдан 01.01.2020. Действует до 31.12.2030. Ошибка 31.02.2030"));
        assertTrue(DateSuggestions.find("без даты").isEmpty());
        assertEquals(java.util.Arrays.asList(LocalDate.of(2030, 12, 31), LocalDate.of(2024, 2, 29)), DateSuggestions.find("2030-12-31 31.12.2030 2024-02-29 2023-02-29 12030-12-31"));
        assertEquals(java.util.Arrays.asList(LocalDate.of(2024, 2, 29), LocalDate.of(2031, 3, 1)), DateSuggestions.find("29 февраля 2024; 31 апреля 2020; 1 МАРТА 2031"));
    }
    @Test public void detectsOnlyUnambiguousExpiry() {
        assertEquals(LocalDate.of(2030, 12, 31), DateSuggestions.findExpiry("Выдан 01.01.2020\nДействует до 31.12.2030"));
        assertEquals(LocalDate.of(2030, 12, 31), DateSuggestions.findExpiry("Срок действия с 01.01.2020 по 31.12.2030"));
        assertEquals(LocalDate.of(2031, 3, 1), DateSuggestions.findExpiry("Дата окончания: 1 марта 2031"));
        assertEquals(LocalDate.of(2031, 3, 1), DateSuggestions.findExpiry("Valid until 01.03.2031"));
        assertNull(DateSuggestions.findExpiry("Дата выдачи 01.01.2020\nДата рождения 31.12.2030"));
        assertNull(DateSuggestions.findExpiry("Действителен с 01.01.2020"));
        assertNull(DateSuggestions.findExpiry("Срок действия: 31.12.2030 и 31.12.2031"));
    }
}
