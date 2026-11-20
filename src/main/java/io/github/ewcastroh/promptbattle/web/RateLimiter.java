package io.github.ewcastroh.promptbattle.web;

import io.github.ewcastroh.promptbattle.game.ErrorCode;
import io.github.ewcastroh.promptbattle.game.GameException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window limits (SPEC §6.4, D-020 #12): 30 POSTs per minute per player token and 120 joins per
 * minute per IP. Other writes are never limited per IP (players share the venue's NAT).
 */
public final class RateLimiter {

    public static final int PLAYER_WRITES_PER_MINUTE = 30;
    public static final int JOINS_PER_MINUTE = 120;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void playerWrite(String tokenKey) {
        check("t:" + tokenKey, PLAYER_WRITES_PER_MINUTE);
    }

    public void join(String ip) {
        check("ip:" + ip, JOINS_PER_MINUTE);
    }

    private void check(String key, int limit) {
        long now = clock.millis();
        long windowMs = WINDOW.toMillis();
        Deque<Long> hits = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            while (!hits.isEmpty() && hits.peekFirst() <= now - windowMs) {
                hits.pollFirst();
            }
            if (hits.size() >= limit) {
                long retryAfter = Math.max(1, (hits.peekFirst() + windowMs - now + 999) / 1000);
                throw new GameException(ErrorCode.RATE_LIMITED, "Rate limit exceeded",
                        Map.of("retryAfterSec", retryAfter));
            }
            hits.addLast(now);
        }
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || e.getValue().peekLast() <= now - windowMs;
                }
            });
        }
    }
}
