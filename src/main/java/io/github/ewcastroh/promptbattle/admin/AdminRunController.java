package io.github.ewcastroh.promptbattle.admin;

import io.github.ewcastroh.promptbattle.game.Game;
import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.Views;
import java.util.function.Consumer;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Own-city run control (API.md §7). Every endpoint returns the admin state. */
@RestController
class AdminRunController {

    record GalleryRequest(Boolean show) {
    }

    private final GameEngine engine;

    AdminRunController(GameEngine engine) {
        this.engine = engine;
    }

    private Views.AdminState act(String city, Consumer<Game> command) {
        return engine.execute(g -> {
            command.accept(g);
            return g.views().adminState(city);
        });
    }

    @PostMapping("/api/admin/rounds/{id}/start")
    Views.AdminState start(@RequestAttribute(AdminAuthInterceptor.CITY) String city, @PathVariable String id) {
        return act(city, g -> g.startRound(city, id));
    }

    @PostMapping("/api/admin/rounds/{id}/close")
    Views.AdminState close(@RequestAttribute(AdminAuthInterceptor.CITY) String city, @PathVariable String id) {
        return act(city, g -> g.closeRound(city, id));
    }

    @PostMapping("/api/admin/rounds/{id}/extend")
    Views.AdminState extend(@RequestAttribute(AdminAuthInterceptor.CITY) String city, @PathVariable String id) {
        return act(city, g -> g.extendRound(city, id));
    }

    @PostMapping("/api/admin/rounds/{id}/rescore")
    Views.AdminState rescore(@RequestAttribute(AdminAuthInterceptor.CITY) String city, @PathVariable String id) {
        return act(city, g -> g.rescore(city, id));
    }

    @PostMapping("/api/admin/mini-podium/start")
    Views.AdminState miniStart(@RequestAttribute(AdminAuthInterceptor.CITY) String city) {
        return act(city, g -> g.startMiniPodium(city));
    }

    @PostMapping("/api/admin/mini-podium/next")
    Views.AdminState miniNext(@RequestAttribute(AdminAuthInterceptor.CITY) String city) {
        return act(city, g -> g.nextMiniPodium(city));
    }

    @PostMapping("/api/admin/podium/start")
    Views.AdminState podiumStart(@RequestAttribute(AdminAuthInterceptor.CITY) String city) {
        return act(city, g -> g.startPodium(city));
    }

    @PostMapping("/api/admin/podium/next")
    Views.AdminState podiumNext(@RequestAttribute(AdminAuthInterceptor.CITY) String city) {
        return act(city, g -> g.nextPodium(city));
    }

    @PostMapping("/api/admin/podium/reset")
    Views.AdminState podiumReset(@RequestAttribute(AdminAuthInterceptor.CITY) String city) {
        return act(city, g -> g.resetPodium(city));
    }

    @PostMapping("/api/admin/practice/gallery")
    Views.AdminState gallery(@RequestAttribute(AdminAuthInterceptor.CITY) String city,
            @RequestBody(required = false) GalleryRequest body) {
        boolean show = body == null || body.show() == null || body.show();
        return act(city, g -> g.showPracticeGallery(city, show));
    }
}
