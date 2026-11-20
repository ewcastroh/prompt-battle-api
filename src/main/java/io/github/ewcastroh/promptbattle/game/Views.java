package io.github.ewcastroh.promptbattle.game;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Immutable read models returned by the API (shapes from docs/API.md). */
public final class Views {

    private Views() {
    }

    static String imageUrl(String imageId) {
        return imageId == null ? null : "/img/" + imageId + ".jpg";
    }

    static String thumbUrl(String imageId) {
        return imageId == null ? null : "/img/" + imageId + "_thumb.jpg";
    }

    public record CityInfo(String code, String name) {
    }

    public record Joined(String playerId, String token, String claimCode, String city) {
    }

    /** Join result; {@code created} is false when an existing token was recognized. */
    public record JoinResult(Joined body, boolean created) {
    }

    // ---- player ----

    public record Me(PlayerInfo player, Phase phase, MeRound round, List<AttemptView> attempts,
            @JsonProperty("final") FinalResult finalResult) {
    }

    public record PlayerInfo(String id, String nickname, String claimCode, String city, String cityName) {
    }

    public record MeRound(String id, String name, boolean scored, String targetUrl, String targetThumbUrl,
            Instant startedAt, Instant endsAt, int attemptsPerPlayer, int attemptsLeft, String entryAttemptId) {
    }

    public record AttemptView(String id, AttemptStatus status, String prompt, Integer queuePosition,
            String imageUrl, String thumbUrl, ErrorCode code, Instant createdAt) {
    }

    public record FinalResult(Integer rank, double total, boolean podium) {
    }

    // ---- stage ----

    public record Stage(CityInfo city, Phase phase, String joinUrl, String qrUrl, int playersCount,
            List<String> nicknames, StageRound round, List<GalleryItem> practiceGallery, StageReveal reveal,
            List<HiddenPrompt> hiddenPrompts) {
    }

    public record StageRound(String id, String name, boolean scored, String targetUrl, Instant startedAt,
            Instant endsAt, int submissionsCount, int attemptsCount, RunStatus status) {
    }

    public record GalleryItem(String nickname, String imageUrl, String thumbUrl) {
    }

    public record StageReveal(RevealKind kind, String roundId, int size, int step, List<RevealEntry> entries) {
    }

    public record RevealEntry(int rank, String nickname, String prompt, String imageUrl, String targetUrl,
            String roundName, double roundScore, Double total, Commentary commentary) {
    }

    public record HiddenPrompt(String roundId, String roundName, String prompt) {
    }

    // ---- admin ----

    public record AdminState(String city, Phase phase, int playersCount, int scoredRoundsDone, AdminRound round,
            RevealPreview miniPodium, RevealPreview podium, boolean practiceGalleryShown) {
    }

    public record AdminRound(String id, String name, boolean scored, RunStatus status, Instant startedAt,
            Instant endsAt, int extensions, String imageProvider, String embeddingProvider, int submissionsCount,
            int inFlight) {
    }

    /** {@code step} is null for a live preview before the reveal starts. */
    public record RevealPreview(String roundId, int size, Integer step, List<PreviewItem> preview) {
    }

    public record PreviewItem(int rank, String playerId, String nickname, String entryId, double score,
            boolean revealed, boolean commentaryReady) {
    }

    public record RoundDefinitionView(String id, int position, String name, boolean scored, String hiddenPrompt,
            int durationSec, int attemptsPerPlayer, long seed, String targetUrl, String targetThumbUrl,
            TargetSource targetSource, boolean locked, CalibrationView calibration, List<RunView> runs) {
    }

    public record CalibrationView(CalibrationStatus status, String provider, List<AnchorView> anchors) {
    }

    public record AnchorView(String prompt, String imageUrl, String thumbUrl, boolean excluded) {
    }

    public record RunView(String city, RunStatus status, Instant startedAt, Instant endsAt, int extensions,
            String imageProvider, String embeddingProvider, Double floor, boolean mixedProviders) {
    }

    public record PreviewView(String previewId, String imageUrl, String thumbUrl, long seed, String provider,
            String model) {
    }

    public record AdminPlayer(String id, String nickname, String claimCode, Instant joinedAt, boolean kicked,
            boolean flagged, int blockedCount, int attemptsCount, double total) {
    }

    public record AdminEntry(String entryId, String playerId, String nickname, String prompt, String promptEn,
            String imageUrl, String thumbUrl, String provider, String model, String embeddingProvider, Double cos,
            Double floor, Double score, boolean hidden, boolean auto, Instant selectedAt) {
    }

    public record AlertView(String id, Instant at, String code, String city, Map<String, Object> params) {
    }
}
