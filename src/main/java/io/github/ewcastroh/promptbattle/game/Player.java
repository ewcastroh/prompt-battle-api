package io.github.ewcastroh.promptbattle.game;

import java.time.Instant;

final class Player {
    String id;
    String city;
    String nickname;
    /** Normalized nickname used for the per-city uniqueness check. */
    String nicknameKey;
    String claimCode;
    Instant joinedAt;
    boolean kicked;
    boolean flagged;

    Player() {
    }
}
