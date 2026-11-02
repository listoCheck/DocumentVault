package ru.documentvault.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class OcrSearchTest {
    @Test public void toleratesConfusablesAndSingleEdit() {
        assertTrue(OcrSearch.matches("Иванов Александр", "1ванов"));
        assertTrue(OcrSearch.matches("договор страхования", "договoр"));
        assertTrue(OcrSearch.matches("страхование", "страхованне"));
        assertFalse(OcrSearch.matches("договор", "паспорт"));
        assertFalse(OcrSearch.matches("договор", ""));
    }
}
