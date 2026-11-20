package io.github.ewcastroh.promptbattle.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

/**
 * Reads a {@code .env} file (UTF-8, the {@code docker compose env_file} format) so local runs need no exported
 * variables. Lines are {@code KEY=value}; blank lines and {@code #} comments are skipped, an optional
 * {@code export } prefix is ignored, and a value may be wrapped in single or double quotes. In an unquoted value,
 * {@code " #"} starts a comment. Registered in {@code META-INF/spring.factories} for the {@code .env} extension.
 */
public final class DotenvPropertySourceLoader implements PropertySourceLoader {

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");

    @Override
    public String[] getFileExtensions() {
        return new String[] {"env"};
    }

    @Override
    public List<PropertySource<?>> load(String name, Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            Map<String, Object> values = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return values.isEmpty() ? List.of() : List.of(new MapPropertySource(name, values));
        }
    }

    static Map<String, Object> parse(String content) {
        Map<String, Object> values = new LinkedHashMap<>();
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (i == 0 && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }
            int eq = line.indexOf('=');
            String key = eq < 0 ? "" : line.substring(0, eq).strip();
            if (!KEY.matcher(key).matches()) {
                // Never echo the line: it may hold a secret.
                throw new IllegalStateException(".env line " + (i + 1) + " is not KEY=value");
            }
            values.put(key, value(line.substring(eq + 1).strip()));
        }
        return values;
    }

    private static String value(String raw) {
        if (raw.length() >= 2) {
            char first = raw.charAt(0);
            if ((first == '"' || first == '\'') && raw.indexOf(first, 1) > 0) {
                return raw.substring(1, raw.indexOf(first, 1));
            }
        }
        int comment = raw.indexOf(" #");
        return (comment >= 0 ? raw.substring(0, comment) : raw).strip();
    }
}
