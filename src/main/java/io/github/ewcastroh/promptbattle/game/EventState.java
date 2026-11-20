package io.github.ewcastroh.promptbattle.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The whole in-memory state: the shared event (round definitions, previews, alerts, provider state) and
 * every city run. Mutated only by {@link Game} on the engine thread; serialized as the snapshot.
 */
public final class EventState {
    List<RoundDefinition> rounds = new ArrayList<>();
    Map<String, CityRun> cities = new LinkedHashMap<>();
    Map<String, Preview> previews = new LinkedHashMap<>();
    List<Alert> alerts = new ArrayList<>();
    /** Event-wide active image provider (SPEC §5.4); circuits themselves are runtime-only. */
    String activeImageProvider;
    /** Embedding provider id → determinism and daily usage. */
    Map<String, EmbeddingProviderState> embeddingProviders = new LinkedHashMap<>();
    int nextRoundPosition = 1;

    public EventState() {
    }

    /** Adds configured cities missing from the state and refreshes display names. */
    public void syncCities(Map<String, String> configured) {
        configured.forEach((code, name) -> {
            CityRun city = cities.get(code);
            if (city == null) {
                cities.put(code, new CityRun(code, name));
            } else {
                city.name = name;
            }
        });
    }
}
