package io.github.ewcastroh.promptbattle.web;

import io.github.ewcastroh.promptbattle.game.ErrorCode;
import io.github.ewcastroh.promptbattle.game.GameException;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** {@code /img/{uuid}.jpg} and {@code /img/{uuid}_thumb.jpg} with immutable caching (API.md §8). */
@RestController
class ImageController {

    private static final Pattern NAME = Pattern.compile("([0-9a-f-]{36})(_thumb)?\\.jpg");
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePublic()
            .immutable();

    private final ImageStore images;

    ImageController(ImageStore images) {
        this.images = images;
    }

    @GetMapping("/img/{name}")
    ResponseEntity<Resource> image(@PathVariable String name) {
        Matcher m = NAME.matcher(name);
        if (!m.matches()) {
            throw new GameException(ErrorCode.NOT_FOUND, "Unknown image");
        }
        return images.file(m.group(1), m.group(2) != null)
                .map(p -> ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(IMMUTABLE)
                        .body((Resource) new FileSystemResource(p)))
                .orElseThrow(() -> new GameException(ErrorCode.NOT_FOUND, "Unknown image"));
    }
}
