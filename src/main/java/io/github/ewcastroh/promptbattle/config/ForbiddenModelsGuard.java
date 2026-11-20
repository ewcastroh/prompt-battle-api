package io.github.ewcastroh.promptbattle.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * SPEC §5.3: refuses to start when a configured model ID matches {@code forbidden-models.txt}. Checks the
 * known model properties and any environment variable whose name ends in {@code _MODEL}.
 */
@Component
public class ForbiddenModelsGuard {

    static final String FILE = "forbidden-models.txt";

    public ForbiddenModelsGuard(AppProperties properties, ConfigurableEnvironment environment) {
        Map<String, String> models = new LinkedHashMap<>(properties.modelIds());
        environment.getPropertySources().forEach(source -> {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.toUpperCase(Locale.ROOT).endsWith("_MODEL") && !models.containsKey(name)) {
                        Object value = enumerable.getProperty(name);
                        if (value != null) {
                            models.put(name, value.toString());
                        }
                    }
                }
            }
        });
        check(models, load());
    }

    static List<Pattern> load() {
        try (InputStream in = new ClassPathResource(FILE).getInputStream()) {
            List<Pattern> patterns = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String entry = line.strip();
                if (entry.isEmpty() || entry.startsWith("#")) {
                    continue;
                }
                String regex = Pattern.quote(entry.toLowerCase(Locale.ROOT)).replace("*", "\\E.*\\Q");
                patterns.add(Pattern.compile(regex));
            }
            return patterns;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + FILE, e);
        }
    }

    /** Throws when any model ID (or its last path segment) matches a forbidden entry. */
    static void check(Map<String, String> models, List<Pattern> forbidden) {
        List<String> offending = new ArrayList<>();
        models.forEach((property, value) -> {
            if (value == null || value.isBlank()) {
                return;
            }
            String id = value.strip().toLowerCase(Locale.ROOT);
            String last = id.substring(id.lastIndexOf('/') + 1);
            for (Pattern p : forbidden) {
                if (p.matcher(id).matches() || p.matcher(last).matches()) {
                    offending.add(property + "=" + value.strip());
                    break;
                }
            }
        });
        if (!offending.isEmpty()) {
            throw new IllegalStateException("Forbidden model IDs configured (see " + FILE + "): " + offending);
        }
    }
}
