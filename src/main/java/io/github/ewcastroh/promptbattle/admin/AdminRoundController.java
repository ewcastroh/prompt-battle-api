package io.github.ewcastroh.promptbattle.admin;

import io.github.ewcastroh.promptbattle.city.GameRuntime;
import io.github.ewcastroh.promptbattle.game.ErrorCode;
import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.GameEngine;
import io.github.ewcastroh.promptbattle.game.GameException;
import io.github.ewcastroh.promptbattle.game.GameViews;
import io.github.ewcastroh.promptbattle.game.Moderation;
import io.github.ewcastroh.promptbattle.game.PromptRules;
import io.github.ewcastroh.promptbattle.game.RoundInput;
import io.github.ewcastroh.promptbattle.game.TargetChoice;
import io.github.ewcastroh.promptbattle.game.Views;
import io.github.ewcastroh.promptbattle.generation.GenerationRequest;
import io.github.ewcastroh.promptbattle.generation.GenerationResult;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Shared round definitions, editable by either admin until a city starts them (API.md §7). */
@RestController
class AdminRoundController {

    static final long MAX_UPLOAD = 10L * 1024 * 1024;
    static final Set<String> UPLOAD_TYPES = Set.of("image/jpeg", "image/png");

    record TargetRequest(String prompt, Long seed) {
    }

    record RoundRequest(String name, String hiddenPrompt, Integer durationSec, Integer attemptsPerPlayer,
            Boolean scored, Long seed, String previewId) {

        RoundInput input() {
            return new RoundInput(name, hiddenPrompt, durationSec, attemptsPerPlayer, scored, seed);
        }
    }

    private final GameEngine engine;
    private final GameRuntime runtime;
    private final ImageStore images;
    private final Moderation moderation;
    private final JsonMapper json;

    AdminRoundController(GameEngine engine, GameRuntime runtime, ImageStore images, Moderation moderation,
            JsonMapper json) {
        this.engine = engine;
        this.runtime = runtime;
        this.images = images;
        this.moderation = moderation;
        this.json = json;
    }

    @PostMapping("/api/admin/rounds/generate-target")
    Views.PreviewView generateTarget(@RequestBody(required = false) TargetRequest body) {
        String prompt = PromptRules.normalize(body == null ? null : body.prompt(), PromptRules.ADMIN_MAX);
        if (moderation.blocksPrompt(prompt)) {
            throw new GameException(ErrorCode.PROMPT_BLOCKED, "Target prompt blocked");
        }
        Long requested = body.seed();
        if (requested != null && (requested < 0 || requested > RoundInput.SEED_MAX)) {
            throw GameException.validation("seed");
        }
        String provider = engine.execute(g -> g.activeImageProvider());
        if (provider == null) {
            throw new GameException(ErrorCode.PROVIDER_UNAVAILABLE, "No image provider available");
        }
        long seed = requested != null ? requested : engine.execute(g -> g.randomSeed());
        GenerationResult result = runtime.generateAndWait(provider, GenerationRequest.square(prompt, seed));
        return switch (result) {
            case GenerationResult.Success s -> {
                String imageId;
                try {
                    imageId = images.store(s.bytes());
                } catch (ImageStore.InvalidImageException e) {
                    throw new GameException(ErrorCode.GENERATION_FAILED, "Unusable image",
                            Map.of("kind", FailureKind.INVALID_RESPONSE.name()));
                }
                yield engine.execute(g -> g.registerPreview(imageId, seed, provider, s.modelId()));
            }
            case GenerationResult.Failure f when f.kind() == FailureKind.SAFETY_BLOCKED ->
                    throw new GameException(ErrorCode.PROMPT_BLOCKED, "Blocked by the provider safety checker");
            case GenerationResult.Failure f -> throw new GameException(ErrorCode.GENERATION_FAILED,
                    "Target generation failed", Map.of("kind", f.kind().name()));
        };
    }

    @GetMapping("/api/admin/rounds")
    List<Views.RoundDefinitionView> rounds() {
        return engine.read(GameViews::roundDefinitions);
    }

    @PostMapping(value = "/api/admin/rounds", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Views.RoundDefinitionView> create(@RequestBody RoundRequest body) {
        TargetChoice target = body.previewId() == null ? null : new TargetChoice.FromPreview(body.previewId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(engine.execute(g -> g.createRound(body.input(), target)));
    }

    @PostMapping(value = "/api/admin/rounds", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Views.RoundDefinitionView> createWithUpload(@RequestPart("round") String round,
            @RequestPart("target") MultipartFile target) {
        RoundInput input = parse(round).input();
        input.validate(true);
        TargetChoice choice = new TargetChoice.Uploaded(storeUpload(target));
        return ResponseEntity.status(HttpStatus.CREATED).body(engine.execute(g -> g.createRound(input, choice)));
    }

    @PutMapping(value = "/api/admin/rounds/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    Views.RoundDefinitionView update(@PathVariable String id, @RequestBody RoundRequest body) {
        TargetChoice target = body.previewId() == null ? null : new TargetChoice.FromPreview(body.previewId());
        return engine.execute(g -> g.updateRound(id, body.input(), target));
    }

    @PutMapping(value = "/api/admin/rounds/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Views.RoundDefinitionView updateWithUpload(@PathVariable String id, @RequestPart("round") String round,
            @RequestPart(value = "target", required = false) MultipartFile target) {
        RoundInput input = parse(round).input();
        if (engine.read(v -> v.roundDefinition(id)).locked()) {
            throw new GameException(ErrorCode.ROUND_LOCKED, "Round already started");
        }
        input.validate(false);
        TargetChoice choice = target == null || target.isEmpty() ? null : new TargetChoice.Uploaded(storeUpload(target));
        return engine.execute(g -> g.updateRound(id, input, choice));
    }

    @DeleteMapping("/api/admin/rounds/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        engine.run(g -> g.deleteRound(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/admin/rounds/{id}/calibrate")
    ResponseEntity<Views.RoundDefinitionView> calibrate(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(engine.execute(g -> g.recalibrate(id)));
    }

    private RoundRequest parse(String round) {
        try {
            RoundRequest request = json.readValue(round, RoundRequest.class);
            if (request == null) {
                throw GameException.validation("round");
            }
            return request;
        } catch (JacksonException e) {
            throw GameException.validation("round");
        }
    }

    /** JPEG or PNG up to 10 MB, center-cropped to a 1024² JPEG (D-020 #8, D-022). */
    private String storeUpload(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_UPLOAD || file.getContentType() == null
                || !UPLOAD_TYPES.contains(file.getContentType().toLowerCase())) {
            throw new GameException(ErrorCode.UPLOAD_INVALID, "Invalid target upload", Map.of("maxMb", 10));
        }
        try {
            return images.store(file.getBytes());
        } catch (IOException | ImageStore.InvalidImageException e) {
            throw new GameException(ErrorCode.UPLOAD_INVALID, "Unreadable target upload", Map.of("maxMb", 10));
        }
    }
}
