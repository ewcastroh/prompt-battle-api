package io.github.ewcastroh.promptbattle.game;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.random.RandomGenerator;

/** Player tokens and claim codes. Only token hashes are stored. */
public final class Tokens {

    /** Unambiguous alphabet: no 0, O, 1, I or L. */
    public static final String CLAIM_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    public static final int CLAIM_LENGTH = 4;

    private Tokens() {
    }

    /** {@code pt_} + 40 base64url chars (30 random bytes): 43 chars. */
    public static String newPlayerToken(RandomGenerator random) {
        byte[] bytes = new byte[30];
        random.nextBytes(bytes);
        return "pt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String newClaimCode(RandomGenerator random) {
        StringBuilder sb = new StringBuilder(CLAIM_LENGTH);
        for (int i = 0; i < CLAIM_LENGTH; i++) {
            sb.append(CLAIM_ALPHABET.charAt(random.nextInt(CLAIM_ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
