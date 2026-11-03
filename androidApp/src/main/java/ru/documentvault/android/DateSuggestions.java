package ru.documentvault.android;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DateSuggestions {
    private static final Pattern DATE = Pattern.compile("(?iu)(?<!\\d)(?:(\\d{4})-(\\d{1,2})-(\\d{1,2})|(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})|(\\d{1,2})\\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\\s+(\\d{4}))(?!\\d)");
    private DateSuggestions() { }
    public static List<LocalDate> find(String text) {
        LinkedHashSet<LocalDate> dates = new LinkedHashSet<>(); Matcher matcher = DATE.matcher(text);
        while (matcher.find()) {
            try {
                boolean iso = matcher.group(1) != null, textual = matcher.group(7) != null;
                int month = textual ? monthNumber(matcher.group(8)) : Integer.parseInt(matcher.group(iso ? 2 : 5));
                LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(textual ? 9 : iso ? 1 : 6)), month, Integer.parseInt(matcher.group(textual ? 7 : iso ? 3 : 4)));
                dates.add(date);
            } catch (DateTimeException ignored) { }
        }
        return new ArrayList<>(dates);
    }
    private static int monthNumber(String month) {
        String[] months = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
        for (int i = 0; i < months.length; i++) if (months[i].equalsIgnoreCase(month)) return i + 1;
        throw new IllegalArgumentException("Неизвестный месяц");
    }
}
