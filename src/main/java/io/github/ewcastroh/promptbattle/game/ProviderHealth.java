package io.github.ewcastroh.promptbattle.game;

/**
 * Read-only view of the image providers' circuit breakers. The domain uses it to pick the next healthy
 * provider on failover (SPEC §5.4); the breakers themselves live in {@code generation}.
 */
@FunctionalInterface
public interface ProviderHealth {

    CircuitState circuit(String providerId);

    ProviderHealth ALL_CLOSED = providerId -> CircuitState.CLOSED;
}
