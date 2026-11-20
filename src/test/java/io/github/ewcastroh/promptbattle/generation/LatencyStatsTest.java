package io.github.ewcastroh.promptbattle.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** PLAN A2 LatencyStats: nearest-rank p50 and p95 over a provider's last 200 calls. */
class LatencyStatsTest {

    private final LatencyStats stats = new LatencyStats();

    @Test
    void withoutSamplesThereAreNoPercentiles() {
        assertThat(stats.p50()).isNull();
        assertThat(stats.p95()).isNull();
    }

    @Test
    void percentilesTakeTheNearestRankOfUnsortedSamples() {
        for (int i = 0; i < 100; i++) {
            stats.record((i * 37) % 100 + 1); // 1..100 ms in a scrambled order
        }

        assertThat(stats.p50()).isEqualTo(50L);
        assertThat(stats.p95()).isEqualTo(95L);
        assertThat(stats.percentile(100)).isEqualTo(100L);
        assertThat(stats.percentile(1)).isEqualTo(1L);
    }

    @Test
    void withFewSamplesTheRankRoundsUp() {
        List.of(400L, 100L, 300L, 200L).forEach(stats::record);

        assertThat(stats.p50()).isEqualTo(200L); // rank ⌈0.50 × 4⌉ = 2
        assertThat(stats.p95()).isEqualTo(400L); // rank ⌈0.95 × 4⌉ = 4
    }

    @Test
    void oneSampleIsEveryPercentile() {
        stats.record(3100);

        assertThat(stats.p50()).isEqualTo(3100L);
        assertThat(stats.p95()).isEqualTo(3100L);
    }

    @Test
    void onlyTheLast200CallsCount() {
        assertThat(LatencyStats.CAPACITY).isEqualTo(200);
        for (int i = 0; i < 200; i++) {
            stats.record(60_000); // a slow start that has scrolled out
        }
        for (int i = 1; i <= 200; i++) {
            stats.record(i);
        }

        assertThat(stats.p50()).isEqualTo(100L);
        assertThat(stats.p95()).isEqualTo(190L);
    }

    @Test
    void clearForgetsEverySample() {
        stats.record(5000);

        stats.clear();

        assertThat(stats.p50()).isNull();
        stats.record(7);
        assertThat(stats.p95()).isEqualTo(7L);
    }
}
