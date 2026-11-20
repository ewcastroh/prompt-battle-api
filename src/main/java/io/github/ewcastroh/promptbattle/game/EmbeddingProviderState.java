package io.github.ewcastroh.promptbattle.game;

import java.time.Instant;

/**
 * Event-wide state of one embedding provider, kept in the snapshot: the last determinism check (SPEC §4.2)
 * and the client-side daily request count (D-013), so a restart neither forgets averaging nor resets the
 * free-tier quota counter.
 */
final class EmbeddingProviderState {
    /** Max absolute difference of the last determinism check, or null when never checked. */
    Double maxDiff;
    boolean averaging;
    Instant checkedAt;
    /** Quota day ({@code yyyy-MM-dd} in the provider's reset zone) of {@link #usedToday}. */
    String usageDay;
    int usedToday;
    /** Day of the last EMBEDDING_QUOTA_LOW alert, so it is raised once per day. */
    String quotaAlertDay;

    EmbeddingProviderState() {
    }
}
