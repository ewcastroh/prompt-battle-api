package io.github.ewcastroh.promptbattle.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ewcastroh.promptbattle.PromptBattleApiApplication;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** SPEC §5.3: the API refuses to start with a forbidden model ID. */
class ForbiddenModelsGuardTest {

    private final List<Pattern> forbidden = ForbiddenModelsGuard.load();

    @ParameterizedTest
    @ValueSource(strings = { "gpt-image-1", "GPT-IMAGE-1.5", "gpt-image-1-mini", "chatgpt-image-latest",
            "gemini-3.1-flash-image-preview", "imagen-4.0-generate-001", "imagen-4", "dall-e-2", "dall-e-3",
            "gemini-2.5-flash-image", "openai/dall-e-3", " gpt-image-1 " })
    void forbiddenIdsAreRejected(String model) {
        assertThatThrownBy(() -> ForbiddenModelsGuard.check(Map.of("OPENAI_IMAGE_MODEL", model), forbidden))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OPENAI_IMAGE_MODEL");
    }

    @ParameterizedTest
    @ValueSource(strings = { "gpt-image-2.5-flare", "fal-ai/flux-2/klein/9b", "flux-2-klein-9b",
            "gemini-embedding-2", "claude-sonnet-5-5", "gemini-nano-banana-2.1", "gpt-image-10" })
    void allowedIdsPass(String model) {
        assertThatCode(() -> ForbiddenModelsGuard.check(Map.of("X_MODEL", model), forbidden))
                .doesNotThrowAnyException();
    }

    @Test
    void startupFailsWithAForbiddenModel() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(PromptBattleApiApplication.class)
                .run("--server.port=0", "--FAL_MODEL=dall-e-3").close())
                .hasStackTraceContaining("FAL_MODEL=dall-e-3");
        assertThatThrownBy(() -> new SpringApplicationBuilder(PromptBattleApiApplication.class)
                .run("--server.port=0", "--OPENAI_IMAGE_MODEL=gpt-image-1").close())
                .hasStackTraceContaining("OPENAI_IMAGE_MODEL=gpt-image-1");
    }
}
