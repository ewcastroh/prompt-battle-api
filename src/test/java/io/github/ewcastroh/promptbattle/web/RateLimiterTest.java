package io.github.ewcastroh.promptbattle.web;

import static io.github.ewcastroh.promptbattle.game.GameAssertions.assertCodePublic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ewcastroh.promptbattle.game.ErrorCode;
import io.github.ewcastroh.promptbattle.game.GameException;
import io.github.ewcastroh.promptbattle.game.MutableClock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** SPEC §6.4: 30 writes/min per token, 120 joins/min per IP, with Retry-After. */
class RateLimiterTest {

    @Test
    void playerWritesAreLimitedPerTokenWithRetryAfter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-12-02T15:00:00Z"));
        RateLimiter limiter = new RateLimiter(clock);
        for (int i = 0; i < 30; i++) {
            limiter.playerWrite("a");
            clock.advanceSeconds(1);
        }
        assertThatThrownBy(() -> limiter.playerWrite("a")).isInstanceOf(GameException.class)
                .satisfies(e -> {
                    GameException g = (GameException) e;
                    assertThat(g.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                    assertThat(g.params()).containsEntry("retryAfterSec", 30L);
                });
        limiter.playerWrite("b"); // another token is independent
        clock.advanceSeconds(30);
        limiter.playerWrite("a");
    }

    @Test
    void joinsAreLimitedPerIp() {
        RateLimiter limiter = new RateLimiter(new MutableClock(Instant.parse("2026-12-02T15:00:00Z")));
        for (int i = 0; i < 120; i++) {
            limiter.join("1.2.3.4");
        }
        assertCodePublic(ErrorCode.RATE_LIMITED, () -> limiter.join("1.2.3.4"));
        limiter.join("5.6.7.8");
    }
}
