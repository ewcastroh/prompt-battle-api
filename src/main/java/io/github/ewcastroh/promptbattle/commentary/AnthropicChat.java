package io.github.ewcastroh.promptbattle.commentary;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import java.time.Duration;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * The Anthropic chat client shared by commentary, LLM moderation and prompt translation (Spring AI over the
 * official Anthropic Java SDK). One short call per use, so: no retries (a retry can't fit the 8 s budget), a
 * per-call timeout, and low effort (thinking stays adaptive; Claude Sonnet 5.5 rejects disabling it). The
 * model id comes from configuration only ({@code COMMENTARY_MODEL}). Sampling parameters are never sent.
 */
public final class AnthropicChat {

    /** Room for adaptive thinking plus a short JSON answer. */
    static final int MAX_TOKENS = 2048;
    /** Where Spring AI keeps the SDK's {@link Message} in the response metadata (synchronous calls). */
    static final String RAW_RESPONSE = "anthropic-response";

    /** The model declined to answer ({@code stop_reason: refusal}, HTTP 200). */
    public static final class ModelRefusedException extends RuntimeException {
        public ModelRefusedException() {
            super("The model refused the request");
        }
    }

    private AnthropicChat() {
    }

    public static ChatClient client(String apiKey, String baseUrl, String model, Duration timeout) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .model(model)
                .maxTokens(MAX_TOKENS)
                .effort(OutputConfig.Effort.LOW)
                .timeout(timeout)
                .maxRetries(0)
                .build();
        return ChatClient.create(AnthropicChatModel.builder().options(options).build());
    }

    /**
     * Calls the model with native structured output into {@code type}. Throws {@link ModelRefusedException}
     * on a refusal (Spring AI returns an empty entity for it), and {@link IllegalStateException} when the
     * reply has no entity. Transport errors and timeouts propagate as they are.
     */
    public static <T> T call(ChatClient.ChatClientRequestSpec request, Class<T> type) {
        ResponseEntity<ChatResponse, T> r = request.call()
                .responseEntity(type, spec -> spec.useProviderStructuredOutput());
        if (refused(r.response())) {
            throw new ModelRefusedException();
        }
        if (r.entity() == null) {
            throw new IllegalStateException("The model returned no " + type.getSimpleName());
        }
        return r.entity();
    }

    /**
     * {@code stop_reason: refusal} on the raw message. Spring AI 2.1 returns an empty response (no
     * generations, no metadata) for a reply without content blocks, which in practice is a refusal too.
     */
    static boolean refused(ChatResponse response) {
        if (response == null || response.getResults().isEmpty()) {
            return true;
        }
        return response.getMetadata().get(RAW_RESPONSE) instanceof Message m
                && m.stopReason().map(StopReason.REFUSAL::equals).orElse(false);
    }
}
