package io.github.ewcastroh.promptbattle.moderation;

import io.github.ewcastroh.promptbattle.game.Moderation;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

/**
 * Deterministic blocklist (SPEC §6.3) from {@code moderation/blocklist.txt}: whole-word match after
 * lowercasing and stripping accents. A3 adds leetspeak and repeated-letter normalization and the content.
 */
public final class BlocklistModeration implements Moderation {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private final Pattern pattern;

    public BlocklistModeration() {
        this(load("moderation/blocklist.txt"));
    }

    public BlocklistModeration(List<String> terms) {
        List<String> quoted = new ArrayList<>();
        for (String t : terms) {
            String n = normalize(t).strip();
            if (!n.isEmpty()) {
                quoted.add(Pattern.quote(n));
            }
        }
        this.pattern = quoted.isEmpty() ? null
                : Pattern.compile("(?<![\\p{L}\\p{Nd}])(" + String.join("|", quoted) + ")(?![\\p{L}\\p{Nd}])");
    }

    static String normalize(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private boolean blocks(String text) {
        return pattern != null && text != null && pattern.matcher(normalize(text)).find();
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
