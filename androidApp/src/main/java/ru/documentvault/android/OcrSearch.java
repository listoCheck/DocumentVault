package ru.documentvault.android;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Same token normalization and edit-distance rule as the desktop prototype. */
public final class OcrSearch {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private OcrSearch() { }
    private static char canonical(char c) {
        switch (c) {
            case 'a': return 'а'; case 'b': return 'в'; case 'c': return 'с'; case 'e': return 'е'; case 'h': return 'н';
            case 'k': return 'к'; case 'm': return 'м'; case 'o': case '0': return 'о'; case 'p': return 'р';
            case 't': return 'т'; case 'x': return 'х'; case 'y': return 'у'; case '3': return 'з'; case '1': return 'и';
            default: return c;
        }
    }
    private static List<String> tokens(String text) {
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('ё', 'е'), Normalizer.Form.NFKC);
        List<String> result = new ArrayList<>(); Matcher matcher = TOKEN.matcher(normalized);
        while (matcher.find()) { String word = matcher.group(); StringBuilder canonical = new StringBuilder();
            for (int i = 0; i < word.length(); i++) canonical.append(canonical(word.charAt(i))); result.add(canonical.toString()); }
        return result;
    }
    public static boolean matches(String text, String query) {
        List<String> terms = tokens(query), words = tokens(text); if (terms.isEmpty()) return false;
        for (String term : terms) {
            boolean matched = false;
            for (String word : words) if (term.equals(word) || (term.length() >= 5 && word.length() >= 5 && distanceAtMostOne(term, word))) { matched = true; break; }
            if (!matched) return false;
        }
        return true;
    }
    private static boolean distanceAtMostOne(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) return false;
        int i = 0, j = 0, errors = 0;
        while (i < a.length() && j < b.length()) {
            if (a.charAt(i) == b.charAt(j)) { i++; j++; continue; }
            if (++errors > 1) return false;
            if (a.length() > b.length()) i++; else if (b.length() > a.length()) j++; else { i++; j++; }
        }
        return errors + a.length() - i + b.length() - j <= 1;
    }
}
