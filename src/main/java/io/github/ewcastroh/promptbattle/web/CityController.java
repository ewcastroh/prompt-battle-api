package io.github.ewcastroh.promptbattle.web;

import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.GameViews;
import io.github.ewcastroh.promptbattle.game.Views;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CityController {

    private final GameEngine engine;

    CityController(GameEngine engine) {
        this.engine = engine;
    }

    @GetMapping("/api/cities")
    List<Views.CityInfo> cities() {
        return engine.read(GameViews::cities);
    }
}
