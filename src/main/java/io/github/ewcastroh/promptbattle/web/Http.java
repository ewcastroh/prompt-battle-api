package io.github.ewcastroh.promptbattle.web;

import io.github.ewcastroh.promptbattle.game.Tokens;

/** Small request helpers. */
public final class Http {

    public static final String NO_CACHE = "no-cache";
    public static final String PRIVATE_NO_CACHE = "private, no-cache";

    private Http() {
    }

    /** The token of an {@code Authorization: Bearer} header, or null. */
    public static String bearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        String value = authorization.strip();
        if (value.length() > 7 && value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return value.substring(7).strip();
        }
        return null;
    }

    /** Rate-limit key for a token: its hash, so raw tokens never sit in memory maps. */
    public static String tokenKey(String token) {
        return token == null ? "anonymous" : Tokens.hash(token);
    }
}
