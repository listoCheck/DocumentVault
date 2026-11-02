package ru.documentvault.android;

import org.junit.Test;
import java.time.LocalDate;
import static org.junit.Assert.*;

public class DateSuggestionsTest {
    @Test public void extractsOnlyValidDates() {
        assertEquals(java.util.Arrays.asList(LocalDate.of(2020, 1, 1), LocalDate.of(2030, 12, 31)), DateSuggestions.find("Выдан 01.01.2020. Действует до 31.12.2030. Ошибка 31.02.2030"));
        assertTrue(DateSuggestions.find("без даты").isEmpty());
    }
}
