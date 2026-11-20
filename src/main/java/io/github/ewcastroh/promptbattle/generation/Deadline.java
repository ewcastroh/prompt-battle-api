package io.github.ewcastroh.promptbattle.generation;

import java.time.Duration;

/** A monotonic point in time bounding one provider call, including its polling and download. */
public final class Deadline {

    private final long deadlineNanos;

    private Deadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static Deadline after(Duration budget) {
        return new Deadline(System.nanoTime() + budget.toNanos());
    }

    /** Time left; zero or negative once expired. */
    public Duration remaining() {
        return Duration.ofNanos(deadlineNanos - System.nanoTime());
    }

    public boolean expired() {
        return deadlineNanos - System.nanoTime() <= 0;
    }

    /** Sleeps for {@code delay}, or until the deadline if that comes first. Returns false when interrupted. */
    public boolean sleep(Duration delay) {
        long nanos = Math.min(delay.toNanos(), deadlineNanos - System.nanoTime());
        if (nanos <= 0) {
            return true;
        }
        try {
            Thread.sleep(Duration.ofNanos(nanos));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
