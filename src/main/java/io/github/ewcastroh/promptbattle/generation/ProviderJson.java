package io.github.ewcastroh.promptbattle.generation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** JSON helpers for the provider adapters (Jackson 3). Parsing never trusts the shape of a response. */
public final class ProviderJson {

    public static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ProviderJson() {
    }

    /** The parsed body, or null when it is not JSON. */
    public static JsonNode parse(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static byte[] bytes(JsonNode node) {
        return MAPPER.writeValueAsBytes(node);
    }

    /** The string value of {@code node.field}, or null when missing or not a string. */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
