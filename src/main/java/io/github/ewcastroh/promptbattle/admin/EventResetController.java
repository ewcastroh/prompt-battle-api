package io.github.ewcastroh.promptbattle.admin;

import io.github.ewcastroh.promptbattle.game.GameEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Global event reset with typed confirmation (API.md §7): deletes every city's data, images and snapshot. */
@RestController
class EventResetController {

    private static final Logger log = LoggerFactory.getLogger(EventResetController.class);

    record ResetRequest(String confirm) {
    }

    private final GameEngine engine;

    EventResetController(GameEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/api/admin/event/reset")
    ResponseEntity<Void> reset(@RequestAttribute(AdminAuthInterceptor.CITY) String city,
            @RequestBody(required = false) ResetRequest body) {
        String confirm = body == null ? null : body.confirm();
        engine.run(g -> g.resetEvent(confirm));
        log.warn("Event reset by the admin of {}", city);
        return ResponseEntity.noContent().build();
    }
}
