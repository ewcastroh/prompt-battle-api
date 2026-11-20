package io.github.ewcastroh.promptbattle.config;

import io.github.ewcastroh.promptbattle.generation.BflImageGenerator;
import io.github.ewcastroh.promptbattle.generation.FalImageGenerator;
import io.github.ewcastroh.promptbattle.generation.ProviderHttp;
import io.github.ewcastroh.promptbattle.generation.RetryPolicy;
import io.github.ewcastroh.promptbattle.scoring.CohereEmbeddingProvider;
import io.github.ewcastroh.promptbattle.scoring.EmbeddingRateLimiter;
import io.github.ewcastroh.promptbattle.scoring.GeminiEmbeddingProvider;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Real provider adapters (every profile except {@code fake}). "Providers without a key are skipped" (SPEC
 * §5.4): an adapter exists only when its key is set, so the chains only see configured providers. Model IDs
 * come from configuration only.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!fake")
public class ProvidersConfig {

    /** Longest a Gemini request waits for the free-tier minute window before failing as RATE_LIMITED. */
    static final Duration GEMINI_MAX_WAIT = Duration.ofSeconds(65);

    @Bean
    ProviderHttp providerHttp() {
        return new ProviderHttp();
    }

    @Bean
    @Conditional(HasFalKey.class)
    FalImageGenerator falImageGenerator(AppProperties props, ProviderHttp http) {
        AppProperties.Fal fal = props.fal();
        return new FalImageGenerator(fal.key(), fal.model(), fal.maxConcurrency(), fal.queueUrl(),
                Duration.ofSeconds(props.image().timeoutSeconds()), fal.pollInterval(), http, RetryPolicy.STANDARD);
    }

    @Bean
    @Conditional(HasBflKey.class)
    BflImageGenerator bflImageGenerator(AppProperties props, ProviderHttp http) {
        AppProperties.Bfl bfl = props.bfl();
        return new BflImageGenerator(bfl.key(), bfl.model(), bfl.maxConcurrency(), bfl.baseUrl(),
                bfl.safetyTolerance(), Duration.ofSeconds(props.image().timeoutSeconds()), bfl.pollInterval(), http,
                RetryPolicy.STANDARD);
    }

    @Bean
    @Conditional(HasGeminiKey.class)
    GeminiEmbeddingProvider geminiEmbeddingProvider(AppProperties props, ProviderHttp http, Clock clock) {
        AppProperties.Gemini g = props.gemini();
        EmbeddingRateLimiter limiter = new EmbeddingRateLimiter(g.embeddingRpm(), g.embeddingTpm(), g.embeddingRpd(),
                g.estimatedTokens(), GEMINI_MAX_WAIT, clock, EmbeddingRateLimiter.Sleeper.REAL);
        return new GeminiEmbeddingProvider(g.apiKey(), g.embeddingModel(), g.embeddingDimensions(), g.baseUrl(),
                Duration.ofSeconds(props.embedding().timeoutSeconds()), http, RetryPolicy.STANDARD, limiter);
    }

    @Bean
    @Conditional(HasCohereKey.class)
    CohereEmbeddingProvider cohereEmbeddingProvider(AppProperties props, ProviderHttp http) {
        AppProperties.Cohere c = props.cohere();
        return new CohereEmbeddingProvider(c.apiKey(), c.embeddingModel(), c.embeddingDimensions(), c.baseUrl(),
                Duration.ofSeconds(props.embedding().timeoutSeconds()), http, RetryPolicy.STANDARD);
    }

    /** Matches when the property has text (the YAML defaults every key to an empty string). */
    abstract static class HasText implements Condition {

        private final String property;

        HasText(String property) {
            this.property = property;
        }

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return StringUtils.hasText(context.getEnvironment().getProperty(property));
        }
    }

    static final class HasFalKey extends HasText {
        HasFalKey() {
            super("app.fal.key");
        }
    }

    static final class HasBflKey extends HasText {
        HasBflKey() {
            super("app.bfl.key");
        }
    }

    static final class HasGeminiKey extends HasText {
        HasGeminiKey() {
            super("app.gemini.api-key");
        }
    }

    static final class HasCohereKey extends HasText {
        HasCohereKey() {
            super("app.cohere.api-key");
        }
    }
}
