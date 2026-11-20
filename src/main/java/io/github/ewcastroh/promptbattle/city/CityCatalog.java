package io.github.ewcastroh.promptbattle.city;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Cities from {@code CITIES=code:name,…} (SPEC §2). Codes are 2–8 lowercase letters. */
public final class CityCatalog {

    private static final Pattern CODE = Pattern.compile("[a-z]{2,8}");
    private final Map<String, String> cities;

    public CityCatalog(String spec) {
        Map<String, String> parsed = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            throw new IllegalStateException("CITIES is empty");
        }
        for (String item : spec.split(",")) {
            int colon = item.indexOf(':');
            String code = (colon < 0 ? item : item.substring(0, colon)).strip();
            String name = colon < 0 ? "" : item.substring(colon + 1).strip();
            if (!CODE.matcher(code).matches()) {
                throw new IllegalStateException("Invalid city code '" + code + "' in CITIES (2-8 lowercase letters)");
            }
            if (name.isEmpty()) {
                throw new IllegalStateException("City '" + code + "' has no display name in CITIES");
            }
            if (parsed.put(code, name) != null) {
                throw new IllegalStateException("Duplicate city code '" + code + "' in CITIES");
            }
        }
        this.cities = Collections.unmodifiableMap(parsed);
    }

    /** Code → display name, in configuration order. */
    public Map<String, String> cities() {
        return cities;
    }
}
