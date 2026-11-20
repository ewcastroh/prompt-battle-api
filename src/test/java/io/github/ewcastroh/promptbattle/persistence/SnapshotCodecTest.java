package io.github.ewcastroh.promptbattle.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ewcastroh.promptbattle.game.EventState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {

    private final SnapshotCodec codec = new SnapshotCodec();

    @Test
    void emptyStateRoundTrips() {
        EventState state = new EventState();
        Map<String, String> cities = new LinkedHashMap<>();
        cities.put("mde", "Medellín");
        cities.put("bog", "Bogotá");
        state.syncCities(cities);
        byte[] json = codec.encode(state);
        assertThat(codec.encode(codec.decode(json))).isEqualTo(json);
        assertThat(new String(json)).contains("\"mde\"", "Medellín");
    }
}
