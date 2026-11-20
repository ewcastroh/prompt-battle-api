package io.github.ewcastroh.promptbattle.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

class DotenvPropertySourceLoaderTest {

    private final DotenvPropertySourceLoader loader = new DotenvPropertySourceLoader();

    @Test
    void readsUtf8KeyValuePairsLikeComposeEnvFile() throws Exception {
        String content = """
                ﻿# comment
                SPRING_PROFILES_ACTIVE=fake

                CITIES=mde:Medellín,bog:Bogotá
                export PORT=8081
                QUOTED="a value # not a comment"
                SINGLE='x=y'
                INLINE=abc # trailing comment
                EMPTY=
                URL=http://localhost:4200
                """;
        List<PropertySource<?>> sources = loader.load(".env",
                new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));

        PropertySource<?> env = sources.getFirst();
        assertThat(env.getProperty("SPRING_PROFILES_ACTIVE")).isEqualTo("fake");
        assertThat(env.getProperty("CITIES")).isEqualTo("mde:Medellín,bog:Bogotá");
        assertThat(env.getProperty("PORT")).isEqualTo("8081");
        assertThat(env.getProperty("QUOTED")).isEqualTo("a value # not a comment");
        assertThat(env.getProperty("SINGLE")).isEqualTo("x=y");
        assertThat(env.getProperty("INLINE")).isEqualTo("abc");
        assertThat(env.getProperty("EMPTY")).isEqualTo("");
        assertThat(env.getProperty("URL")).isEqualTo("http://localhost:4200");
    }

    @Test
    void rejectsMalformedLinesWithoutEchoingThem() {
        assertThatThrownBy(() -> DotenvPropertySourceLoader.parse("OK=1\nsecret-without-key\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(".env line 2 is not KEY=value");
    }

    @Test
    void emptyFileGivesNoSource() throws Exception {
        assertThat(loader.load(".env", new ByteArrayResource(new byte[0]))).isEmpty();
    }
}
