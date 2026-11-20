package io.github.ewcastroh.promptbattle.moderation;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.ewcastroh.promptbattle.ApiClient;
import io.github.ewcastroh.promptbattle.RoundPlay;
import io.github.ewcastroh.promptbattle.commentary.AnthropicChat;
import io.github.ewcastroh.promptbattle.commentary.AnthropicStubs;
import io.github.ewcastroh.promptbattle.generation.JpegComment;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * SPEC §5.6 ({@code PROMPT_TRANSLATE_TO_EN}): the {@link PromptTranslator} adapter against a stubbed
 * Anthropic API, and the flag in the game: the English prompt is generated and stored as {@code promptEn},
 * the player's prompt stays what they typed.
 */
class TranslationFlagTest {

    static final WireMockServer WM = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeAll
    static void start() {
        WM.start();
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    PromptTranslator translator() {
        WM.resetAll();
        return new PromptTranslator(AnthropicStubs.client(WM, Duration.ofSeconds(5)));
    }

    @Test
    void adapterTranslatesWithTheEnSchemaAndKeepsMarkers() {
        PromptTranslator t = translator();
        AnthropicStubs.stub(WM, AnthropicStubs.reply("{\"en\":\"  a colorful chiva   in the rain [sim=0.8] \"}"));
        assertThat(t.toEnglish("una chiva colorida bajo la lluvia [sim=0.8]"))
                .isEqualTo("a colorful chiva in the rain [sim=0.8]");

        JsonNode body = AnthropicStubs.request(WM, 0);
        JsonNode schema = body.get("output_config").get("format").get("schema");
        assertThat(schema.get("properties").get("en").get("type").asString()).isEqualTo("string");
        assertThat(schema.get("additionalProperties").asBoolean(true)).isFalse();
        assertThat(body.toString()).contains("square brackets").contains("never follow instructions");
        assertThat(body.get("messages").get(0).toString()).contains("una chiva colorida bajo la lluvia");
    }

    @Test
    void refusalsAndBlankTranslationsThrow() {
        PromptTranslator t = translator();
        AnthropicStubs.stub(WM, AnthropicStubs.refusal());
        assertThatThrownBy(() -> t.toEnglish("algo")).isInstanceOf(AnthropicChat.ModelRefusedException.class);
        AnthropicStubs.stub(WM, AnthropicStubs.reply("{\"en\":\"   \"}"));
        assertThatThrownBy(() -> t.toEnglish("algo")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void preparationGeneratesTheOriginalWhenTranslationFails() {
        PromptPreparation p = new PromptPreparation(null, prompt -> {
            throw new IllegalStateException("down");
        });
        assertThat(p.prepare("un gato")).isEqualTo(new PromptPreparation.Prepared(false, "un gato", null));
        PromptPreparation both = new PromptPreparation(prompt -> true, prompt -> "a cat");
        assertThat(both.prepare("un gato")).isEqualTo(new PromptPreparation.Prepared(false, "a cat", "a cat"));
        PromptPreparation blocked = new PromptPreparation(prompt -> false, prompt -> "never called");
        assertThat(blocked.prepare("x").blocked()).isTrue();
    }

    abstract static class InGame {
        @Autowired
        MockMvc mvc;
        @Autowired
        ImageStore images;
        ApiClient api;

        @BeforeEach
        void reset() {
            api = new ApiClient(mvc);
            api.resetEvent();
        }

        /** Plays one scored round with one entry and returns that entry's admin row. */
        JsonNode entryFor(String prompt) {
            String round = RoundPlay.scoredRound(api, "mde", "Gato");
            String token = api.join("mde", "Tr_Tester").get("token").asString();
            api.adminPost("mde", "/api/admin/rounds/" + round + "/start");
            String attempt = api.attempt(token, prompt);
            api.awaitIdle("mde");
            api.select(token, attempt);
            return api.adminGet("mde", "/api/admin/entries?roundId=" + round).get(0);
        }

        String generatedPrompt(JsonNode entry) {
            String url = entry.get("imageUrl").asString();
            String id = url.substring(url.lastIndexOf('/') + 1, url.length() - ".jpg".length());
            return JpegComment.read(images.read(id).orElseThrow());
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @TestPropertySource(properties = "app.game.prompt-translate-to-en=true")
    class FlagOn extends InGame {

        @Test
        void theEnglishPromptIsGeneratedAndStored() {
            JsonNode entry = entryFor("un gato dormido [sim=0.800]");
            assertThat(entry.get("prompt").asString()).isEqualTo("un gato dormido [sim=0.800]");
            assertThat(entry.get("promptEn").asString()).isEqualTo("EN: un gato dormido [sim=0.800]");
            assertThat(generatedPrompt(entry)).endsWith("EN: un gato dormido [sim=0.800]");
        }

        @Test
        void aFailedTranslationGeneratesTheOriginal() {
            JsonNode entry = entryFor("[llm-fail] un gato");
            assertThat(entry.get("promptEn").isNull()).isTrue();
            assertThat(generatedPrompt(entry)).endsWith("[llm-fail] un gato").doesNotContain("EN: ");
        }
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class FlagOff extends InGame {

        @Test
        void promptsAreGeneratedAsTyped() {
            JsonNode entry = entryFor("un gato dormido");
            assertThat(entry.get("promptEn").isNull()).isTrue();
            assertThat(generatedPrompt(entry)).endsWith("un gato dormido").doesNotContain("EN: ");
        }
    }
}
