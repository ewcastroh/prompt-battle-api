package io.github.ewcastroh.promptbattle.commentary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ewcastroh.promptbattle.game.Commentary;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** The canned comments, read straight from the resource files, to check a comment's band and pairing. */
public final class CannedLines {

    private static final TreeMap<Integer, List<String>> ES = load("commentary/fallback-es.txt");
    private static final TreeMap<Integer, List<String>> EN = load("commentary/fallback-en.txt");

    private CannedLines() {
    }

    /** Asserts that {@code es}/{@code en} are line n of the score's band in each file (the same n). */
    public static void assertCanned(String es, String en, double score) {
        Map.Entry<Integer, List<String>> band = ES.floorEntry((int) Math.floor(score));
        assertThat(band.getValue()).as("es comment in band %s for score %s", band.getKey(), score).contains(es);
        int index = band.getValue().indexOf(es);
        assertThat(en).as("en comment pairs with the es one").isEqualTo(EN.get(band.getKey()).get(index));
    }

    public static void assertCanned(Commentary c, double score) {
        assertCanned(c.es(), c.en(), score);
    }

    public static int band(double score) {
        return ES.floorKey((int) Math.floor(score));
    }

    private static TreeMap<Integer, List<String>> load(String path) {
        TreeMap<Integer, List<String>> bands = new TreeMap<>();
        try (InputStream in = CannedLines.class.getClassLoader().getResourceAsStream(path)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    int bar = l.indexOf('|');
                    bands.computeIfAbsent(Integer.parseInt(l.substring(0, bar).strip()), k -> new ArrayList<>())
                            .add(l.substring(bar + 1).strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bands;
    }
}
