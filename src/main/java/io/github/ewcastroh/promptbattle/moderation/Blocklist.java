package io.github.ewcastroh.promptbattle.moderation;

import io.github.ewcastroh.promptbattle.game.Moderation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * Deterministic blocklist for nicknames and prompts (SPEC §6.3 step 1), from {@code moderation/blocklist.txt}.
 * <p>Both the text and the terms go through {@link TextNormalizer}. A term matches as a whole word (or phrase)
 * where every run of one letter in the term may be repeated in the text: {@code puta} matches "puuuta" and
 * "PUT4", but {@code ass} needs two s's, so it never blocks "as". Words of a phrase may be separated by spaces,
 * {@code _ . -} or nothing ("hijo de puta", "hijo_de_puta", "hijodeputa").
 */
public final class Blocklist implements Moderation {

    private static final String WORD_CHAR = "[\\p{L}\\p{Nd}]";
    private static final String SEPARATORS = "[\\s_.\\-]*";
    private final Pattern pattern;
    private final int size;

    public Blocklist() {
        this(load("moderation/blocklist.txt"));
    }

    public Blocklist(List<String> terms) {
        List<String> regexes = new ArrayList<>();
        for (String t : terms) {
            String regex = termRegex(TextNormalizer.normalize(t).strip());
            if (!regex.isEmpty()) {
                regexes.add(regex);
            }
        }
        this.size = regexes.size();
        this.pattern = regexes.isEmpty() ? null
                : Pattern.compile("(?<!" + WORD_CHAR + ")(?:" + String.join("|", regexes) + ")(?!" + WORD_CHAR + ")");
    }

    /** Number of terms loaded. */
    public int size() {
        return size;
    }

    /** {@code ass} → {@code as{2,}}, {@code puta} → {@code p+u+t+a+}; spaces become optional separators. */
    static String termRegex(String term) {
        StringBuilder regex = new StringBuilder();
        int[] cps = term.codePoints().toArray();
        int i = 0;
        while (i < cps.length) {
            int c = cps[i];
            int run = 1;
            while (i + run < cps.length && cps[i + run] == c) {
                run++;
            }
            if (Character.isWhitespace(c)) {
                regex.append(SEPARATORS);
            } else {
                regex.append(Pattern.quote(new String(Character.toChars(c))));
                if (Character.isLetter(c)) {
                    regex.append(run == 1 ? "+" : "{" + run + ",}");
                } else if (run > 1) {
                    regex.append("{").append(run).append("}");
                }
            }
            i += run;
        }
        return regex.toString();
    }

    public boolean blocks(String text) {
        return pattern != null && text != null && pattern.matcher(TextNormalizer.normalize(text)).find();
    }

    @Override
    public boolean blocksNickname(String nickname) {
        return blocks(nickname);
    }

    @Override
    public boolean blocksPrompt(String prompt) {
        return blocks(prompt);
    }

    private static List<String> load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            List<String> terms = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    terms.add(l);
                }
            }
            return terms;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
    }
}
