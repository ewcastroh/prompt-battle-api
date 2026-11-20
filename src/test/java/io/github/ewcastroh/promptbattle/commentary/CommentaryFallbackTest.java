package io.github.ewcastroh.promptbattle.commentary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.RoundPlay;
import io.github.ewcastroh.promptbattle.game.Commentary;
import io.github.ewcastroh.promptbattle.game.Effect;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * SPEC §4.4 and PLAN A3: when the live commentary model throws, times out or refuses, every mini-podium and
 * podium step still gets a comment: the canned one of its score band, in Spanish and English (the same line
 * of both files), and the admins get {@code COMMENTARY_FALLBACK}.
 */
@SpringBootTest(properties = "app.commentary.timeout=300ms")
@AutoConfigureMockMvc
class CommentaryFallbackTest {

    enum Failure { THROWS, TIMES_OUT, REFUSED }

    static volatile Failure failure = Failure.THROWS;
    static final AtomicInteger CALLS = new AtomicInteger();

    /** A live model that always fails in the chosen way. */
    @TestConfiguration
    static class FailingModel {
        @Bean
        @Primary
        CommentaryPort failingCommentary() {
            return new CommentaryPort() {
                @Override
                public boolean live() {
                    return true;
                }

                @Override
                public Commentary comment(Effect.CommentaryJob job) {
                    CALLS.incrementAndGet();
                    switch (failure) {
                        case THROWS -> throw new IllegalStateException("model down");
                        case REFUSED -> throw new AnthropicChat.ModelRefusedException();
                        case TIMES_OUT -> {
                            try {
                                Thread.sleep(30_000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return new Commentary("tarde", "late");
                        }
                    }
                    throw new AssertionError();
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient(mvc);
        api.resetEvent();
        CALLS.set(0);
    }

    @ParameterizedTest
    @EnumSource(Failure.class)
    void everyStepGetsTheCannedCommentOfItsBandInBothLanguages(Failure mode) {
        failure = mode;
        String round = RoundPlay.scoredRound(api, "mde", "Chiva en la lluvia");
        List<RoundPlay.Player> players = RoundPlay.join(api, "mde", "fb", 4);
        // One score per band: ≥ 85, 70–85, 50–70, < 50.
        RoundPlay.play(api, "mde", round, players, 90.0, 75.0, 60.0, 30.0);

        api.adminPost("mde", "/api/admin/mini-podium/start");
        for (int i = 0; i < 3; i++) {
            api.adminPost("mde", "/api/admin/mini-podium/next");
        }
        assertCannedEntries(api.stage("mde").get("reveal"), 3);

        api.adminPost("mde", "/api/admin/podium/start");
        api.awaitPhase("mde", "PODIUM_REVEAL");
        for (int i = 0; i < 4; i++) {
            api.adminPost("mde", "/api/admin/podium/next");
        }
        JsonNode reveal = api.stage("mde").get("reveal");
        assertThat(reveal.get("kind").asString()).isEqualTo("PODIUM");
        assertCannedEntries(reveal, 4);

        assertThat(CALLS.get()).as("the model was asked for every step (3 mini + 4 final)").isEqualTo(7);
        int fallbacks = 0;
        for (JsonNode alert : api.adminGet("mde", "/api/admin/alerts")) {
            if (alert.get("code").asString().equals("COMMENTARY_FALLBACK")) {
                assertThat(alert.get("city").asString()).isEqualTo("mde");
                fallbacks += alert.get("params").get("count").asInt();
            }
        }
        assertThat(fallbacks).isEqualTo(7);
    }

    private static void assertCannedEntries(JsonNode reveal, int count) {
        JsonNode entries = reveal.get("entries");
        assertThat(entries).hasSize(count);
        for (JsonNode e : entries) {
            JsonNode c = e.get("commentary");
            CannedLines.assertCanned(c.get("es").asString(), c.get("en").asString(), e.get("roundScore").asDouble());
        }
    }
}
