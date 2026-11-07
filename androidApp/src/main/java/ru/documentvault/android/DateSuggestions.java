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
    /** Return an expiry only when a clear expiry label identifies a single date. */
    public static LocalDate findExpiry(String text) {
        if (text == null || text.isEmpty()) return null;
        Pattern label = Pattern.compile("(?iu)(?:действителен|действительна|действительно|действует|срок\\s+действия|годен|годна|истекает|дата\\s+окончания|expiry|expiration|valid\\s+(?:until|through|to)|expires)");
        Pattern until = Pattern.compile("(?iu)(?:(?<![\\p{L}])до(?![\\p{L}])|(?<![\\p{L}])по(?![\\p{L}])|until|through|expires?\\s+on|expiry\\s+date|expiration\\s+date)");
        LinkedHashSet<LocalDate> candidates = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            Matcher labels = label.matcher(line);
            while (labels.find()) {
                String tail = line.substring(labels.end());
                if (tail.length() > 120) tail = tail.substring(0, 120);
                Matcher cut = Pattern.compile("(?iu)(?:выдан|выдана|дата\\s+рождения|issued|birth)").matcher(tail);
                if (cut.find()) tail = tail.substring(0, cut.start());
                Matcher markerMatch = until.matcher(tail);
                if (markerMatch.find()) tail = tail.substring(markerMatch.end());
                else if (line.substring(labels.start(), labels.end()).matches("(?iu).*действу.*|.*действител.*|.*годен.*|.*годна.*")) continue;
                List<LocalDate> dates = find(tail);
                if (dates.size() == 1) candidates.add(dates.get(0));
                else if (dates.size() > 1) return null;
            }
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }
    private static int monthNumber(String month) {
        String[] months = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
        for (int i = 0; i < months.length; i++) if (months[i].equalsIgnoreCase(month)) return i + 1;
        throw new IllegalArgumentException("Неизвестный месяц");
    }
}
