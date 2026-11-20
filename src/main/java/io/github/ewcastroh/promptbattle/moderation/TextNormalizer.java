package io.github.ewcastroh.promptbattle.moderation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalization before blocklist matching (SPEC §6.3): lowercase, accents stripped, basic leetspeak
 * ({@code 4→a 3→e 1→i 0→o 5→s @→a}). Repeated letters are handled by {@link Blocklist}, which lets each
 * letter of a term match a longer run of the same letter.
 */
public final class TextNormalizer {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern REPEATS = Pattern.compile("(\\p{L})\\1+");

    private TextNormalizer() {
    }

    public static String normalize(String text) {
        String stripped = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
        StringBuilder out = new StringBuilder(stripped.length());
        stripped.toLowerCase(Locale.ROOT).codePoints().forEach(c -> out.appendCodePoint(switch (c) {
            case '4', '@' -> 'a';
            case '3' -> 'e';
            case '1' -> 'i';
            case '0' -> 'o';
            case '5' -> 's';
            default -> c;
        }));
        return out.toString();
    }

    /** Collapses each run of the same letter to one letter ({@code "puuuta"} → {@code "puta"}). */
    public static String collapse(String text) {
        return REPEATS.matcher(text).replaceAll("$1");
    }
}
