package io.github.ewcastroh.promptbattle.city;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ewcastroh.promptbattle.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spring may stop and start lifecycle beans more than once (Spring Framework 7 pauses unused test contexts and
 * restarts them on reuse). The runtime must survive that: the engine keeps running and the timers come back.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GameRuntimeLifecycleTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ConfigurableApplicationContext context;
    @Autowired
    GameRuntime runtime;

    @Test
    void stopThenStartKeepsTheGamePlayable() {
        ApiClient api = new ApiClient(mvc);
        api.resetEvent();
        String round = api.createRound("mde", "Ciclo", "a lifecycle target", false).get("id").asString();
        api.join("mde", "Before_Stop");

        context.stop();
        assertThat(runtime.isRunning()).isFalse();
        context.start();
        assertThat(runtime.isRunning()).isTrue();

        String token = api.join("mde", "After_Start").get("token").asString();
        api.adminPost("mde", "/api/admin/rounds/" + round + "/start");
        api.attempt(token, "a cat after a restart");
        api.awaitIdle("mde"); // generation results come back through the engine
        api.adminPost("mde", "/api/admin/rounds/" + round + "/close");
        api.awaitPhase("mde", "LOBBY"); // the timer-driven close finishes
        assertThat(api.adminGet("mde", "/api/admin/players")).hasSize(2);
    }
}
