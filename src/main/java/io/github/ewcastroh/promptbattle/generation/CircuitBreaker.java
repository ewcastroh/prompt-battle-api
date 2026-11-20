package io.github.ewcastroh.promptbattle.generation;

import io.github.ewcastroh.promptbattle.game.CircuitState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;

/**
 * Circuit breaker of one image provider (SPEC §5.4): {@value #FAILURES} failures of kind TIMEOUT,
 * PROVIDER_ERROR, RATE_LIMITED or INVALID_RESPONSE within 30 s open it for 60 s; then it is HALF_OPEN and
 * exactly one trial call decides between CLOSED and OPEN. SAFETY_BLOCKED is never a failure (the provider
 * answered). Thread-safe; never calls out while holding its lock.
 */
public final class CircuitBreaker {

    public static final int FAILURES = 3;
    public static final Duration WINDOW = Duration.ofSeconds(30);
    public static final Duration OPEN_FOR = Duration.ofSeconds(60);

    /** What a recorded result changed, so the caller can raise CIRCUIT_OPEN / CIRCUIT_CLOSED. */
    public enum Transition {
        NONE,
        OPENED,
        CLOSED
    }

    /** How a call was dispatched. Only a TRIAL (or a PROBE) result can close an open circuit. */
    public enum Role {
        NORMAL,
        TRIAL,
        PROBE
    }

    private final Clock clock;
    private final ArrayDeque<Instant> failures = new ArrayDeque<>();
    private CircuitState state = CircuitState.CLOSED;
    private Instant openedAt;
    private boolean trialInFlight;

    public CircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    public synchronized CircuitState state() {
        refresh();
        return state;
    }

    /** Whether a normal call may be dispatched now: closed, or half-open with no trial in flight. */
    public synchronized boolean allows() {
        refresh();
        return state == CircuitState.CLOSED || (state == CircuitState.HALF_OPEN && !trialInFlight);
    }

    /** Called when a call is dispatched; a call on a half-open circuit becomes its single trial. */
    public synchronized Role onDispatch(boolean probe) {
        refresh();
        if (probe) {
            return Role.PROBE;
        }
        if (state == CircuitState.HALF_OPEN && !trialInFlight) {
            trialInFlight = true;
            return Role.TRIAL;
        }
        return Role.NORMAL;
    }

    /**
     * Records the result of a call. Results of calls dispatched before the circuit opened are ignored while
     * it is open or half-open: only the trial (or an admin's probe) decides.
     */
    public synchronized Transition record(boolean failure, Role role) {
        refresh();
        if (role == Role.TRIAL) {
            trialInFlight = false;
        }
        Instant now = clock.instant();
        if (!failure) {
            if (state != CircuitState.CLOSED && role != Role.NORMAL) {
                close();
                return Transition.CLOSED;
            }
            return Transition.NONE;
        }
        switch (state) {
            case CLOSED -> {
                failures.addLast(now);
                while (!failures.isEmpty() && !failures.peekFirst().isAfter(now.minus(WINDOW))) {
                    failures.removeFirst();
                }
                if (failures.size() >= FAILURES) {
                    open(now);
                    return Transition.OPENED;
                }
            }
            case HALF_OPEN -> {
                if (role == Role.TRIAL) {
                    open(now); // still unhealthy: another open period, no new alert
                }
            }
            case OPEN -> {
                // already open
            }
        }
        return Transition.NONE;
    }

    /** An admin's forced switch: closes the circuit at once. */
    public synchronized Transition reset() {
        refresh();
        boolean wasClosed = state == CircuitState.CLOSED;
        close();
        return wasClosed ? Transition.NONE : Transition.CLOSED;
    }

    private void open(Instant now) {
        state = CircuitState.OPEN;
        openedAt = now;
        trialInFlight = false;
        failures.clear();
    }

    private void close() {
        state = CircuitState.CLOSED;
        openedAt = null;
        trialInFlight = false;
        failures.clear();
    }

    private void refresh() {
        if (state == CircuitState.OPEN && !clock.instant().isBefore(openedAt.plus(OPEN_FOR))) {
            state = CircuitState.HALF_OPEN;
            trialInFlight = false;
        }
    }
}
