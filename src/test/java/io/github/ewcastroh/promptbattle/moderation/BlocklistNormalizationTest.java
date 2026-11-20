package io.github.ewcastroh.promptbattle.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SPEC §6.3 step 1 (PLAN A3): the blocklist matches after lowercasing, stripping accents, basic leetspeak
 * and repeated letters, for nicknames and prompts, as whole words, without blocking everyday words.
 */
class BlocklistNormalizationTest {

    static final Blocklist BLOCKLIST = new Blocklist();

    @Test
    void normalizerLowercasesStripsAccentsAndMapsLeetspeak() {
        assertThat(TextNormalizer.normalize("PÚTÁ")).isEqualTo("puta");
        assertThat(TextNormalizer.normalize("Maricón")).isEqualTo("maricon");
        assertThat(TextNormalizer.normalize("4 3 1 0 5 @")).isEqualTo("a e i o s a");
        assertThat(TextNormalizer.normalize("P0RN0")).isEqualTo("porno");
        assertThat(TextNormalizer.normalize("año")).isEqualTo("ano");
        assertThat(TextNormalizer.collapse("puuuuttaaa")).isEqualTo("puta");
    }

    @Test
    void termsAllowRepeatsOfEachLetterRun() {
        assertThat(Blocklist.termRegex("puta")).isEqualTo("\\Qp\\E+\\Qu\\E+\\Qt\\E+\\Qa\\E+");
        assertThat(Blocklist.termRegex("ass")).isEqualTo("\\Qa\\E+\\Qs\\E{2,}");
        assertThat(Blocklist.termRegex("hijo de")).contains("[\\s_.\\-]*");
    }

    @ParameterizedTest
    @ValueSource(strings = { "PUTA", "Pútá", "maricón", "MALPARIDO", "Pornografía", "cocaína" })
    void accentsAndCaseDontMatter(String text) {
        assertThat(BLOCKLIST.blocksPrompt(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "put4", "p0rn0", "m13rd4", "5exy", "@sshole", "fuck3d", "n4k3d", "h1jueput4" })
    void leetspeakIsDecoded(String text) {
        assertThat(BLOCKLIST.blocksPrompt(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "puuuuta", "fuuuuuck", "shiiiit", "pornooo", "mierrrrda", "asssss", "gonorreeea" })
    void repeatedLettersAreCollapsed(String text) {
        assertThat(BLOCKLIST.blocksPrompt(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = { "hijo de puta", "Hijo-De-Puta", "hijo_de_puta", "hijodeputa", "hijo   de  puuta" })
    void phrasesMatchAcrossSeparators(String text) {
        assertThat(BLOCKLIST.blocksPrompt(text)).as(text).isTrue();
    }

    @Test
    void nicknamesAreChecked() {
        assertThat(BLOCKLIST.blocksNickname("Put4_Master")).isTrue();
        assertThat(BLOCKLIST.blocksNickname("xX_PUUTA_Xx")).isTrue();
        assertThat(BLOCKLIST.blocksNickname("Mr.Malparido")).isTrue();
        assertThat(BLOCKLIST.blocksNickname("Ana_dev")).isFalse();
        assertThat(BLOCKLIST.blocksNickname("Pixel_Paisa")).isFalse();
        assertThat(BLOCKLIST.blocksNickname("Disputa_99")).isFalse();
    }

    @Test
    void promptsAreChecked() {
        assertThat(BLOCKLIST.blocksPrompt("un gato fumando cocaína en la playa")).isTrue();
        assertThat(BLOCKLIST.blocksPrompt("a NAKED man on a horse")).isTrue();
        assertThat(BLOCKLIST.blocksPrompt("una chiva colorida bajo la lluvia, estilo acuarela")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "un cono de helado de fresa", "feliz año nuevo en Medellín", "una zorra roja en el bosque",
            "una perra con sus cachorros", "una bomba de colores en una fiesta infantil", "café con perico",
            "voy a coger el bus", "qué chimba de chiva", "Moby Dick, the white whale", "a cock crowing at dawn",
            "as fast as a cat", "pass the glass of class", "a classic Essex cottage", "the therapist's office",
            "Charles Dickens reading a book", "una disputa entre computadoras en Putumayo", "el sexto piso",
            "a red bicycle", "abstract colorful shapes", "un perro caliente", "el 5 de mayo a las 10",
            "a heroine saving the city", "a method actor", "a gorilla eating bananas"})
    void everydayWordsAreNotBlocked(String text) {
        assertThat(BLOCKLIST.blocksPrompt(text)).as(text).isFalse();
    }

    @Test
    void everyTermInTheFileBlocksItselfAndItsVariants() throws IOException {
        List<String> terms = terms();
        assertThat(terms).hasSizeGreaterThan(100);
        assertThat(BLOCKLIST.size()).isEqualTo(terms.size());
        for (String term : terms) {
            assertThat(BLOCKLIST.blocksPrompt(term)).as(term).isTrue();
            assertThat(BLOCKLIST.blocksPrompt("una imagen de " + term.toUpperCase(Locale.ROOT) + ", por favor"))
                    .as("inside a sentence: %s", term).isTrue();
            String stretched = term + term.charAt(term.length() - 1) + term.charAt(term.length() - 1);
            assertThat(BLOCKLIST.blocksPrompt(stretched)).as("stretched: %s", stretched).isTrue();
        }
    }

    @Test
    void anEmptyListBlocksNothing() {
        Blocklist empty = new Blocklist(List.of());
        assertThat(empty.blocksPrompt("puta")).isFalse();
        assertThat(empty.blocksNickname(null)).isFalse();
    }

    static List<String> terms() throws IOException {
        List<String> terms = new ArrayList<>();
        try (InputStream in = BlocklistNormalizationTest.class.getClassLoader()
                .getResourceAsStream("moderation/blocklist.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String l = line.strip();
                if (!l.isEmpty() && !l.startsWith("#")) {
                    terms.add(l);
                }
            }
        }
        return terms;
    }
}
