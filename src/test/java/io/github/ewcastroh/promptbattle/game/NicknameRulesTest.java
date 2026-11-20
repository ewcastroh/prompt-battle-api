package io.github.ewcastroh.promptbattle.game;

import static io.github.ewcastroh.promptbattle.game.GameAssertions.assertCode;
import static io.github.ewcastroh.promptbattle.game.GameFixture.BOG;
import static io.github.ewcastroh.promptbattle.game.GameFixture.MDE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SPEC §3.1: 3–16 letters/digits/space/_/-, trimmed, unique per city ignoring case and accents. */
class NicknameRulesTest {

    @ParameterizedTest
    @ValueSource(strings = { "Ana", "José_99", "  Pixel Paisa  ", "a-b", "Ñandú", "1234567890123456", "日本語" })
    void validNicknames(String nickname) {
        assertThat(NicknameRules.validate(nickname)).isEqualTo(nickname.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "  ", "ab", "  ab  ", "12345678901234567", "ana!", "a.b", "emoji😀", "tab\tx",
            "<script>" })
    void invalidNicknames(String nickname) {
        assertCode(ErrorCode.NICKNAME_INVALID, () -> NicknameRules.validate(nickname));
    }

    @Test
    void nullIsInvalidWithParams() {
        assertCode(ErrorCode.NICKNAME_INVALID, () -> NicknameRules.validate(null));
        try {
            NicknameRules.validate("x");
        } catch (GameException e) {
            assertThat(e.params()).containsEntry("min", 3).containsEntry("max", 16);
        }
    }

    @Test
    void keyIgnoresCaseAndAccents() {
        assertThat(NicknameRules.key("JOSÉ")).isEqualTo(NicknameRules.key("jose"));
        assertThat(NicknameRules.key("Ñandú")).isEqualTo("nandu");
    }

    @Test
    void uniqueWithinACityButNotAcrossCities() {
        GameFixture fx = new GameFixture();
        fx.join(MDE, "José");
        assertCode(ErrorCode.NICKNAME_TAKEN, () -> fx.join(MDE, "jose"));
        assertCode(ErrorCode.NICKNAME_TAKEN, () -> fx.join(MDE, " JOSÉ "));
        assertThat(fx.join(BOG, "jose")).as("the same nickname in another city is allowed").startsWith("pt_");
    }

    @Test
    void renameFollowsTheSameRules() {
        GameFixture fx = new GameFixture();
        String ana = fx.join(MDE, "Ana");
        fx.join(MDE, "Beto");
        String anaId = fx.playerId(ana);
        assertCode(ErrorCode.NICKNAME_TAKEN, () -> fx.game.renamePlayer(MDE, anaId, "BETO"));
        assertCode(ErrorCode.NICKNAME_INVALID, () -> fx.game.renamePlayer(MDE, anaId, "x"));
        assertThat(fx.game.renamePlayer(MDE, anaId, "ANA").nickname()).as("own nickname, other case").isEqualTo("ANA");
        fx.blockedWords.add("bad");
        assertCode(ErrorCode.NICKNAME_BLOCKED, () -> fx.game.renamePlayer(MDE, anaId, "badboy"));
        assertCode(ErrorCode.NICKNAME_BLOCKED, () -> fx.join(MDE, "badgirl"));
    }

    @Test
    void unknownCity() {
        GameFixture fx = new GameFixture();
        assertCode(ErrorCode.CITY_NOT_FOUND, () -> fx.join("cali", "Ana"));
    }
}
