package io.github.ewcastroh.promptbattle.commentary;

import io.github.ewcastroh.promptbattle.game.CannedCommentary;
import io.github.ewcastroh.promptbattle.game.Commentary;
import io.github.ewcastroh.promptbattle.game.Effect;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.io.ClassPathResource;

/**
 * Canned comments by score band (≥ 85, 70–85, 50–70, &lt; 50) from {@code commentary/fallback-es.txt} and
 * {@code -en.txt}. Lines are {@code <band>|<text>}; the n-th line of a band in one file is the translation of
 * the n-th line of the same band in the other. The choice is deterministic per key.
 */
public final class FallbackCommentary implements CannedCommentary, CommentaryPort {

    private final TreeMap<Integer, List<String>> es;
    private final TreeMap<Integer, List<String>> en;

    public FallbackCommentary() {
        this.es = load("commentary/fallback-es.txt");
        this.en = load("commentary/fallback-en.txt");
        if (!es.keySet().equals(en.keySet())) {
            throw new IllegalStateException("Fallback commentary files have different bands");
        }
        es.forEach((band, lines) -> {
            if (lines.size() != en.get(band).size() || lines.isEmpty()) {
                throw new IllegalStateException("Fallback commentary band " + band + " differs between es and en");
            }
        });
    }

    @Override
    public Commentary canned(String key, double score) {
        Map.Entry<Integer, List<String>> band = es.floorEntry((int) Math.floor(score));
        if (band == null) {
            band = es.firstEntry();
        }
        int index = Math.floorMod(key.hashCode(), band.getValue().size());
        return new Commentary(band.getValue().get(index), en.get(band.getKey()).get(index));
    }

    @Override
    public Commentary comment(Effect.CommentaryJob job) {
        return canned(job.key(), job.score());
    }

    private static TreeMap<Integer, List<String>> load(String path) {
        TreeMap<Integer, List<String>> bands = new TreeMap<>();
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String l = line.strip();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                int bar = l.indexOf('|');
                if (bar < 1) {
                    throw new IllegalStateException("Bad line in " + path + ": " + l);
                }
                bands.computeIfAbsent(Integer.parseInt(l.substring(0, bar).strip()), k -> new ArrayList<>())
                        .add(l.substring(bar + 1).strip());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        return bands;
    }
}
