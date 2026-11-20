package io.github.ewcastroh.promptbattle.game;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** An admin alert (API.md §7, alert codes). {@code city} is null for event-wide alerts. */
final class Alert {
    String id;
    Instant at;
    String code;
    String city;
    Map<String, Object> params = new LinkedHashMap<>();

    Alert() {
    }
}
