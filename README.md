# prompt-battle-api

Game server for **Prompt Battle: ¡Compila tu Imaginación!**, a live game played at the same time in Medellín and Bogotá (EPAM Community Palooza, 2026-12-02). Players write prompts on their phones, the server generates every image with the same model and seed, scores how close each image is to a target, and each city's admin reveals that city's top 5 on the projector.

Part of [prompt-battle-kit](https://github.com/ewcastroh/prompt-battle-kit), which holds the spec, the API contract (`docs/API.md`) and the decisions. The UI is [prompt-battle-ui](https://github.com/ewcastroh/prompt-battle-ui).

## Stack
Java 25 · Spring Boot 4.2.0 · Maven wrapper · ZXing (QR). No database: one instance keeps the whole event in memory and snapshots it to `DATA_DIR`.

## Run locally (no keys needed)

Settings come from a `.env` file in the folder you run from; nothing needs to be exported.

```bash
cp .env.example .env
# edit .env: set ADMIN_TOKEN_MDE and ADMIN_TOKEN_BOG (24+ characters each, different)
./mvnw spring-boot:run
curl -s localhost:8080/actuator/health   # {"status":"UP",...}
curl -s localhost:8080/api/cities
curl -s -H "X-Admin-Token: <your ADMIN_TOKEN_MDE>" localhost:8080/api/admin/me
```

Or build once and run the jar from this folder: `./mvnw -B package -DskipTests && java -jar target/prompt-battle-api-0.0.1-SNAPSHOT.jar`.

The `fake` profile replaces every provider: images are generated locally and scoring is deterministic. To play with known scores, generate the target from a prompt containing `[target]` and write player prompts with `[sim=0.83]` (→ score 83.0).

From the parent folder, `docker compose up --build` runs the API with the UI (after the integration phase).

## Test

```bash
./mvnw -B verify
```

Includes the state machine and its 409s, per-city isolation, attempt accounting, close and grace rules, ranking and tie-breaks, scoring, nickname rules, admin auth, the round lock, snapshot restore, CORS, ETag, the API contract shapes and a full 2 cities × 50 players simulation; and for the providers: WireMock tests of every adapter (fal, BFL, Gemini, Cohere), the circuit breaker and event-wide failover, the shared queue and its per-provider limits, the load split, embedding failover and consistency, the Gemini free-tier limiter, the determinism check and the probe. Tests never call real providers.

## Providers
- **Images:** fal.ai through its queue API (`FAL_KEY`) and Black Forest Labs (`BFL_API_KEY`), same model (FLUX.2 [klein] 9B). `IMAGE_PROVIDER_CHAIN` is the failover order; with `IMAGE_LOAD_SPLIT` each job goes to the active provider and overflows to the other listed one when its slots (`FAL_MAX_CONCURRENCY`, `BFL_MAX_CONCURRENCY`) are full. A provider without a key is skipped.
- **Failover:** 3 failures in 30 s open a provider's circuit for 60 s and switch the active provider for both cities; either admin can force a provider (`POST /api/admin/providers/active`). Safety blocks never count.
- **Embeddings:** Gemini (`GEMINI_API_KEY`, free tier, client-side RPM/TPM/RPD limits) with Cohere as optional backup. A city run is scored with one provider; if it fails the whole run is re-embedded with the next one, or it stays `SCORING_FAILED` until an admin rescores.
- **Probe:** `POST /api/admin/providers/probe` (spends real credits) generates one image per provider, embeds a test image twice (determinism check) and reports latency and errors.

## Configuration
Every setting is an environment variable (see `.env.example` and `docs/SPEC.md` §11.1). Locally they are read from `./.env` (UTF-8, `KEY=value`, the `docker compose` `env_file` format); a real environment variable, as on Railway, overrides the same key in `.env`. Tests never read `.env`. Required: `ADMIN_TOKEN_<CITY>` for each city in `CITIES` (at least 24 characters, all different). The API refuses to start if a configured model ID is listed in `src/main/resources/forbidden-models.txt`.

## Deploy notes
- **Exactly one instance** (replicas = 1, serverless off) with a persistent volume at `DATA_DIR`. A second instance would split the game state.
- Health check: `/actuator/health`.
- Never commit secrets: this repository is public. Keys live in the hosting dashboard and GitHub Actions secrets.

## Status
Phases A1 (game core) and A2 (real providers, failover, scoring, probe). AI commentary, moderation content, CSV export and the Dockerfile come in A3.
