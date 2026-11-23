package io.github.ewcastroh.promptbattle.config;

import io.github.ewcastroh.promptbattle.generation.FakeImageGenerator;
import io.github.ewcastroh.promptbattle.moderation.FakeLanguageModel;
import io.github.ewcastroh.promptbattle.scoring.FakeEmbeddingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Profile {@code fake}: every provider is fake; no keys needed; fully playable offline (SPEC §11.1). */
@Configuration(proxyBeanMethods = false)
@Profile("fake")
public class FakeProvidersConfig {

    @Bean
    FakeImageGenerator fakeImageGenerator(AppProperties props) {
        AppProperties.Fake fake = props.fake();
        return new FakeImageGenerator(fake.model(), fake.latencyMs(), fake.failureRate(), fake.maxConcurrency());
    }

    /**
     * A second fake image provider with its own slot limit, so a load run can use the split the event uses
     * ({@code FAKE_IMAGE_PROVIDER_CHAIN=fake,fake2}, {@code FAKE_IMAGE_LOAD_SPLIT=fake,fake2}). Unused unless listed.
     */
    @Bean
    FakeImageGenerator secondFakeImageGenerator(AppProperties props) {
        AppProperties.Fake fake = props.fake();
        return new FakeImageGenerator(FakeImageGenerator.SECOND_ID, fake.model(), fake.latencyMs(),
                fake.failureRate(), fake.secondMaxConcurrency());
    }

    /** Offline LLM moderation and translation; used only when their flags are on. Commentary stays canned. */
    @Bean
    FakeLanguageModel fakeLanguageModel() {
        return new FakeLanguageModel();
    }

    @Bean
    FakeEmbeddingProvider fakeEmbeddingProvider(AppProperties props) {
        return new FakeEmbeddingProvider(props.fake().model(), props.fake().embeddingDimensions());
    }
}
