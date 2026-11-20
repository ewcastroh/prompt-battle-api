package io.github.ewcastroh.promptbattle.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class ClaimCodeTest {

    @Test
    void claimCodesUseTheUnambiguousAlphabet() {
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 2000; i++) {
            assertThat(Tokens.newClaimCode(random)).hasSize(4).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{4}")
                    .doesNotContain("0", "O", "1", "I", "L");
        }
    }

    @Test
    void claimCodesAreUniqueWithinACity() {
        GameFixture fx = new GameFixture();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            codes.add(fx.game.join("mde", "Player" + i, null).body().claimCode());
        }
        assertThat(codes).hasSize(300);
    }

    @Test
    void tokensAreOpaqueAndStoredOnlyAsHashes() {
        GameFixture fx = new GameFixture();
        String token = fx.join("mde", "Ana");
        assertThat(token).startsWith("pt_").hasSize(43);
        assertThat(fx.cityJson("mde")).doesNotContain(token).contains(Tokens.hash(token));
    }
}
