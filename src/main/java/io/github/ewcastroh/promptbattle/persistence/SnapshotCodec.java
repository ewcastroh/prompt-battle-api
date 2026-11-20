package io.github.ewcastroh.promptbattle.persistence;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import io.github.ewcastroh.promptbattle.game.EventState;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON form of the whole {@link EventState}. Reads and writes fields directly, so the domain needs no
 * getters, setters or annotations. Properties are sorted and maps keep insertion order (join order matters),
 * so the output is deterministic and can be compared.
 */
public final class SnapshotCodec {

    private final JsonMapper mapper = JsonMapper.builder()
            .changeDefaultVisibility(v -> v.withVisibility(PropertyAccessor.ALL, Visibility.NONE)
                    .withVisibility(PropertyAccessor.FIELD, Visibility.ANY)
                    .withVisibility(PropertyAccessor.CREATOR, Visibility.ANY))
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public byte[] encode(Object state) {
        return mapper.writeValueAsBytes(state);
    }

    public String encodeToString(Object state) {
        return mapper.writeValueAsString(state);
    }

    public EventState decode(byte[] json) {
        return mapper.readValue(json, EventState.class);
    }
}
