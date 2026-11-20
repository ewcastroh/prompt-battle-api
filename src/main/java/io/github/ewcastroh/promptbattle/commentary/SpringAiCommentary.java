package io.github.ewcastroh.promptbattle.commentary;

import io.github.ewcastroh.promptbattle.game.Commentary;
import io.github.ewcastroh.promptbattle.game.Effect;
import io.github.ewcastroh.promptbattle.game.Moderation;
import io.github.ewcastroh.promptbattle.persistence.ImageStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

/**
 * Podium commentary from the commentary model (SPEC §4.4): the target image, the entry image, the score and
 * the rank in; one call returns Spanish (es-CO) and English through native structured output
 * ({@code {es,en}}, no other properties). Any failure (timeout, refusal, invalid or blocklisted text)
 * throws; {@code GameRuntime} then uses the canned comment for that score band.
 * <p>The 384 px thumbnails are sent: enough for a comment, and about 7× fewer image tokens than 1024 px.
 */
public final class SpringAiCommentary implements CommentaryPort {

    /** Both texts in one reply. Field order and names are the JSON schema sent to the model. */
    record Reply(String es, String en) {
    }

    /** Thrown when the model's reply can't go on the stage; the caller falls back to a canned comment. */
    public static final class InvalidCommentaryException extends RuntimeException {
        InvalidCommentaryException(String message) {
            super(message);
        }
    }

    /** SPEC asks for ≤ 20 words; small overruns are tolerated, rambling replies are not. */
    static final int MAX_WORDS = 30;

    static final String SYSTEM = """
            You write the comment shown on the projector when a player reaches the podium of Prompt Battle, a live \
            game at a tech community event in Colombia. Players type prompts trying to recreate a target image with \
            an image generator. You get two images: the first is the target, the second is the player's image.

            Write one comment about how the player's image compares with the target: a detail that matches, a fun \
            difference, the mood. Be playful, warm and kind. Talk only about the images.
            Never mention people's bodies or looks, politics, religion, or anything insulting or mocking. Don't \
            repeat the score or the rank as numbers. Emojis aren't needed.

            Return JSON with "es": the comment in Colombian Spanish, and "en": the same comment in English. Each is \
            one sentence of at most 20 words.""";

    private final ChatClient chat;
    private final ImageStore images;
    private final Moderation blocklist;

    public SpringAiCommentary(ChatClient chat, ImageStore images, Moderation blocklist) {
        this.chat = chat;
        this.images = images;
        this.blocklist = blocklist;
    }

    @Override
    public boolean live() {
        return true;
    }

    @Override
    public Commentary comment(Effect.CommentaryJob job) {
        Media target = jpeg(job.targetImageId());
        Media entry = jpeg(job.entryImageId());
        String context = String.format(Locale.ROOT,
                "Podium rank %d. Similarity score %.1f out of 100.%s Image 1 is the target, image 2 is the player's.",
                job.rank(), job.score(),
                job.total() == null ? "" : String.format(Locale.ROOT, " Event total %.1f.", job.total()));
        Reply reply = AnthropicChat.call(chat.prompt()
                .system(SYSTEM)
                .user(u -> u.text(context).media(target, entry)), Reply.class);
        return new Commentary(checked(reply.es()), checked(reply.en()));
    }

    private String checked(String text) {
        String t = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            throw new InvalidCommentaryException("blank comment");
        }
        if (t.split(" ").length > MAX_WORDS) {
            throw new InvalidCommentaryException("comment too long");
        }
        if (blocklist.blocksPrompt(t)) {
            throw new InvalidCommentaryException("comment hit the blocklist");
        }
        return t;
    }

    private Media jpeg(String imageId) {
        Path file = images.file(imageId, true).or(() -> images.file(imageId, false))
                .orElseThrow(() -> new IllegalStateException("Image not found: " + imageId));
        try {
            return new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read image " + imageId, e);
        }
    }
}
