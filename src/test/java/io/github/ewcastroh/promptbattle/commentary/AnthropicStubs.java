package io.github.ewcastroh.promptbattle.commentary;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import java.time.Duration;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Anthropic Messages API stubs for WireMock. Replies look like Claude Sonnet 5.5's: an (empty, display
 * "omitted") adaptive-thinking block before the text block. Tests never call the real API.
 */
public final class AnthropicStubs {

    public static final String KEY = "test-anthropic-key";
    public static final String MODEL = "claude-sonnet-5-5";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private AnthropicStubs() {
    }

    public static ChatClient client(WireMockServer wm, Duration timeout) {
        return AnthropicChat.client(KEY, wm.baseUrl(), MODEL, timeout);
    }

    /** A 200 whose text block is {@code text} (structured output arrives as JSON text). */
    public static ResponseDefinitionBuilder reply(String text) {
        return message("end_turn", List.of(thinking(), textBlock(text)));
    }

    /** The model declined (HTTP 200, {@code stop_reason: refusal}) without any content. */
    public static ResponseDefinitionBuilder refusal() {
        return message("refusal", List.of(), refusalDetails());
    }

    /** A refusal that stopped mid-answer: partial (here, valid-looking) JSON text, then {@code refusal}. */
    public static ResponseDefinitionBuilder partialRefusal(String partialText) {
        return message("refusal", List.of(textBlock(partialText)), refusalDetails());
    }

    private static ObjectNode refusalDetails() {
        ObjectNode details = JSON.createObjectNode().put("type", "refusal").put("explanation", "declined");
        details.putNull("category");
        return details;
    }

    public static void stub(WireMockServer wm, ResponseDefinitionBuilder response) {
        wm.stubFor(post(urlEqualTo("/v1/messages")).willReturn(response));
    }

    /** The JSON body of the n-th request to the Messages API. */
    public static JsonNode request(WireMockServer wm, int index) {
        var events = wm.getAllServeEvents().reversed(); // WireMock lists newest first
        return JSON.readTree(events.get(index).getRequest().getBodyAsString());
    }

    private static ObjectNode thinking() {
        return JSON.createObjectNode().put("type", "thinking").put("thinking", "").put("signature", "c2lnbmF0dXJl");
    }

    private static ObjectNode textBlock(String text) {
        return JSON.createObjectNode().put("type", "text").put("text", text);
    }

    private static ResponseDefinitionBuilder message(String stopReason, List<ObjectNode> content) {
        return message(stopReason, content, null);
    }

    private static ResponseDefinitionBuilder message(String stopReason, List<ObjectNode> content, ObjectNode details) {
        ObjectNode m = JSON.createObjectNode()
                .put("id", "msg_test")
                .put("type", "message")
                .put("role", "assistant")
                .put("model", MODEL)
                .put("stop_reason", stopReason);
        m.putNull("stop_sequence");
        m.putArray("content").addAll(content);
        if (details != null) {
            m.set("stop_details", details);
        }
        m.putObject("usage").put("input_tokens", 1200).put("output_tokens", 40);
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withHeader("request-id", "req_test").withBody(JSON.writeValueAsString(m));
    }
}
