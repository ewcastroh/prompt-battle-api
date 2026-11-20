package io.github.ewcastroh.promptbattle.admin;

import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.Views;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Own-city players and entries (API.md §7). */
@RestController
class AdminPlayerController {

    record RenameRequest(String nickname) {
    }

    record HideRequest(Boolean hidden) {
    }

    private final GameEngine engine;

    AdminPlayerController(GameEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/api/admin/players")
    List<Views.AdminPlayer> players(@RequestAttribute(AdminAuthInterceptor.CITY) String city) {
        return engine.read(v -> v.players(city));
    }

    @PostMapping("/api/admin/players/{id}/rename")
    Views.AdminPlayer rename(@RequestAttribute(AdminAuthInterceptor.CITY) String city, @PathVariable String id,
            @RequestBody(required = false) RenameRequest body) {
        String nickname = body == null ? null : body.nickname();
        return engine.execute(g -> g.renamePlayer(city, id, nickname));
    }

    @PostMapping("/api/admin/players/{id}/kick")
    ResponseEntity<Void> kick(@RequestAttribute(AdminAuthInterceptor.CITY) String city, @PathVariable String id) {
        engine.run(g -> g.kickPlayer(city, id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/admin/entries")
    List<Views.AdminEntry> entries(@RequestAttribute(AdminAuthInterceptor.CITY) String city,
            @RequestParam String roundId) {
        return engine.read(v -> v.entries(city, roundId));
    }

    @PostMapping("/api/admin/entries/{id}/hide")
    ResponseEntity<Void> hide(@RequestAttribute(AdminAuthInterceptor.CITY) String city, @PathVariable String id,
            @RequestBody(required = false) HideRequest body) {
        boolean hidden = body == null || body.hidden() == null || body.hidden();
        engine.run(g -> g.hideEntry(city, id, hidden));
        return ResponseEntity.noContent().build();
    }
}
