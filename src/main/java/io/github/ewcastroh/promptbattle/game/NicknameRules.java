package io.github.ewcastroh.promptbattle.game;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Nickname rules (SPEC §3.1): 3–16 letters, digits, space, '_' or '-', trimmed. */
public final class NicknameRules {

    public static final int MIN = 3;
    public static final int MAX = 16;
    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{M}\\p{Nd} _-]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private NicknameRules() {
    }

    /** Returns the trimmed nickname or throws {@code NICKNAME_INVALID}. */
    public static String validate(String raw) {
        String nickname = raw == null ? "" : Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
        int length = nickname.codePointCount(0, nickname.length());
        if (length < MIN || length > MAX || !ALLOWED.matcher(nickname).matches()) {
            throw new GameException(ErrorCode.NICKNAME_INVALID, "Nickname has invalid length or characters",
                    Map.of("min", MIN, "max", MAX));
        }
        return nickname;
    }

    /** Case- and accent-insensitive key for the per-city uniqueness check. */
    public static String key(String nickname) {
        String decomposed = Normalizer.normalize(nickname, Normalizer.Form.NFD);
        return MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
