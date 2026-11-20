package io.github.ewcastroh.promptbattle.admin;

import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.Views;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import io.github.ewcastroh.promptbattle.web.Http;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AdminStateController {

    record AdminMe(String city, String cityName) {
    }

    private final GameEngine engine;

    AdminStateController(GameEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/api/admin/me")
    AdminMe me(@RequestAttribute(AdminAuthInterceptor.CITY) String city) {
        Views.CityInfo info = engine.read(v -> v.cityInfo(city));
        return new AdminMe(info.code(), info.name());
    }

    @GetMapping("/api/admin/state")
    ResponseEntity<Views.AdminState> state(@RequestAttribute(AdminAuthInterceptor.CITY) String city) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, Http.PRIVATE_NO_CACHE)
                .body(engine.read(v -> v.adminState(city)));
    }

    @GetMapping("/api/admin/alerts")
    List<Views.AlertView> alerts(@RequestParam(required = false) String since) {
        Instant from;
        try {
            from = since == null || since.isBlank() ? null : Instant.parse(since);
        } catch (DateTimeParseException e) {
            throw io.github.ewcastroh.promptbattle.game.GameException.validation("since");
        }
        return engine.read(v -> v.alerts(from));
    }
}
