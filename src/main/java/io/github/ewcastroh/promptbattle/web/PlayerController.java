package io.github.ewcastroh.promptbattle.web;

import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.Views;
import io.github.ewcastroh.promptbattle.generation.GenerationQueue;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Player endpoints (API.md §5). */
@RestController
class PlayerController {

    record JoinRequest(String nickname) {
    }

    record AttemptRequest(String prompt) {
    }

    record Submitted(String attemptId, Integer queuePosition) {
    }

    record EntryRequest(String attemptId) {
    }

    private final GameEngine engine;
    private final GenerationQueue queue;
    private final RateLimiter limiter;

    PlayerController(GameEngine engine, GenerationQueue queue, RateLimiter limiter) {
        this.engine = engine;
        this.queue = queue;
        this.limiter = limiter;
    }

    @PostMapping("/api/cities/{city}/players")
    ResponseEntity<Views.Joined> join(@PathVariable String city, @RequestBody(required = false) JoinRequest body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        limiter.join(request.getRemoteAddr());
        String nickname = body == null ? null : body.nickname();
        String token = Http.bearer(authorization);
        Views.JoinResult result = engine.execute(g -> g.join(city, nickname, token));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @GetMapping("/api/me")
    ResponseEntity<Views.Me> me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        String token = Http.bearer(auth);
        Views.Me me = engine.read(v -> v.me(token));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, Http.PRIVATE_NO_CACHE).body(me);
    }

    @PostMapping("/api/rounds/current/attempts")
    ResponseEntity<Submitted> attempt(@RequestBody(required = false) AttemptRequest body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        String token = Http.bearer(auth);
        limiter.playerWrite(Http.tokenKey(token));
        String prompt = body == null ? null : body.prompt();
        String attemptId = engine.execute(g -> g.submitAttempt(token, prompt));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Submitted(attemptId, queue.positionOf(attemptId)));
    }

    @GetMapping("/api/attempts/{id}")
    Views.AttemptView attempt(@PathVariable String id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        String token = Http.bearer(auth);
        return engine.read(v -> v.attempt(token, id));
    }

    @PostMapping("/api/rounds/current/entry")
    ResponseEntity<Void> entry(@RequestBody(required = false) EntryRequest body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth) {
        String token = Http.bearer(auth);
        limiter.playerWrite(Http.tokenKey(token));
        String attemptId = body == null ? null : body.attemptId();
        engine.run(g -> g.selectEntry(token, attemptId));
        return ResponseEntity.noContent().build();
    }
}
