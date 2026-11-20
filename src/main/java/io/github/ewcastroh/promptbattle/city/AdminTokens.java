package io.github.ewcastroh.promptbattle.city;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.env.Environment;

/**
 * Admin tokens from {@code ADMIN_TOKEN_<CODE>} (SPEC §6.4): one per city, required, at least 24 chars.
 * The token decides the admin's city. Comparison is constant-time over SHA-256 digests.
 */
public final class AdminTokens {

    public static final int MIN_LENGTH = 24;
    private final Map<String, byte[]> digests = new LinkedHashMap<>();

    public AdminTokens(Environment environment, CityCatalog catalog) {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String code : catalog.cities().keySet()) {
            String variable = "ADMIN_TOKEN_" + code.toUpperCase(Locale.ROOT);
            String token = environment.getProperty(variable);
            if (token == null || token.isBlank()) {
                throw new IllegalStateException(variable + " is required");
            }
            if (token.length() < MIN_LENGTH) {
                throw new IllegalStateException(variable + " must be at least " + MIN_LENGTH + " characters");
            }
            if (seen.containsKey(token)) {
                throw new IllegalStateException(variable + " must differ from ADMIN_TOKEN_"
                        + seen.get(token).toUpperCase(Locale.ROOT));
            }
            seen.put(token, code);
            digests.put(code, sha256(token));
        }
    }

    /** The city whose admin token this is, if any. Every city is compared, so timing does not leak. */
    public Optional<String> cityFor(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        byte[] candidate = sha256(token);
        String match = null;
        for (Map.Entry<String, byte[]> e : digests.entrySet()) {
            if (MessageDigest.isEqual(candidate, e.getValue())) {
                match = e.getKey();
            }
        }
        return Optional.ofNullable(match);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
