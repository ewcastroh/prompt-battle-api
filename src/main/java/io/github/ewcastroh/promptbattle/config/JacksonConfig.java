package io.github.ewcastroh.promptbattle.config;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/** API.md §1: instants are ISO-8601 UTC with milliseconds, e.g. {@code 2026-12-02T15:04:05.123Z}. */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    static final DateTimeFormatter INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    @Bean
    JsonMapperBuilderCustomizer instantWithMillis() {
        SimpleModule module = new SimpleModule("instant-millis");
        module.addSerializer(Instant.class, new StdSerializer<>(Instant.class) {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeString(INSTANT.format(value));
            }
        });
        return builder -> builder.addModule(module);
    }
}
