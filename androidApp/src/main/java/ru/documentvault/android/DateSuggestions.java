package ru.documentvault.android;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DateSuggestions {
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})(?!\\d)");
    private DateSuggestions() { }
    public static List<LocalDate> find(String text) {
        List<LocalDate> dates = new ArrayList<>(); Matcher matcher = DATE.matcher(text);
        while (matcher.find()) {
            try {
                LocalDate date = LocalDate.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)));
                if (!dates.contains(date)) dates.add(date);
            } catch (DateTimeException ignored) { }
        }
        return dates;
    }
}
