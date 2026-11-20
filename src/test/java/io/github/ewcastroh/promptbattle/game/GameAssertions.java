package io.github.ewcastroh.promptbattle.game;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

public final class GameAssertions {

    private GameAssertions() {
    }

    public static void assertCodePublic(ErrorCode code, ThrowingCallable call) {
        assertCode(code, call);
    }

    static void assertCode(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(GameException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((GameException) e).code())
                        .as("error code").isEqualTo(code));
    }
}
