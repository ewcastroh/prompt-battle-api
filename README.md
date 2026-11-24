# prompt-battle-api

Game server for **Prompt Battle: ¡Compila tu Imaginación!**, a live game played at the same time in Medellín and Bogotá (EPAM Community Palooza, 2026-12-02). Players write prompts on their phones, the server generates every image with the same model and seed, scores how close each image is to a target, and each city's admin reveals that city's top 5 on the projector.

Part of [prompt-battle-kit](https://github.com/ewcastroh/prompt-battle-kit), which holds the spec, the API contract (`docs/API.md`) and the decisions. The UI is [prompt-battle-ui](https://github.com/ewcastroh/prompt-battle-ui).

## Stack
Java 25 · Spring Boot 4.2.0 · Spring AI 2.1.0 (Anthropic) · Maven wrapper · ZXing (QR). No database: one instance keeps the whole event in memory and snapshots it to `DATA_DIR`.

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

To test from a phone on the same Wi-Fi with the UI's `npm run start:lan`, set these in this folder's `.env` (`<LAN IP>` from `ipconfig getifaddr en0` on macOS), then restart the API:

```bash
PUBLIC_UI_URL=http://<LAN IP>:4200                              # the stage QR code points here
CORS_ALLOWED_ORIGINS=http://localhost:4200,http://<LAN IP>:4200
```

With Docker Compose, set `LAN_HOST` in the parent `.env` instead. See `docs/DEPLOYMENT.md` → "Testing on your phone" in the parent repo.

### Docker

```bash
docker build -t prompt-battle-api .
docker run --rm -p 8080:8080 --env-file .env -v "$PWD/data:/data" prompt-battle-api
```

The image builds the jar with Temurin 25 and runs it on the Temurin 25 JRE as an unprivileged user (uid 10001). `DATA_DIR` is `/data`, a volume. **Run exactly one container**: the game state lives in memory.

## Test

```bash
./mvnw -B verify
```

Includes the state machine and its 409s, per-city isolation, attempt accounting, close and grace rules, ranking and tie-breaks, scoring, nickname rules, admin auth, the round lock, snapshot restore, CORS, ETag, the API contract shapes and a full 2 cities × 50 players simulation; and for the providers: WireMock tests of every adapter (fal, BFL, Gemini, Cohere), the circuit breaker and event-wide failover, the shared queue and its per-provider limits, the load split, embedding failover and consistency, the Gemini free-tier limiter, the determinism check and the probe; and for A3: the Anthropic commentary adapter against WireMock (request schema, images, auth, refusals, no retries, timeouts), the canned fallback in both languages, that no reveal waits on an AI call, the blocklist normalization, the LLM moderation and translation flags, the results CSV and the event reset. Tests never call real providers.

## Load test

`scripts/loadtest.sh` (SPEC §14.3) plays 2 cities × 50 phones × 3 attempts within 120 s against a **running** API: every phone polls `/api/me` every 1.5 s with `If-None-Match`, stages every 1.5 s and admins every 2 s (≈ 75 requests/s). It creates one scored round, starts it in every city, closes both at once and prints p50/p95/max from "Generate" to image visible, errors and close → scored per city. Exit code 0 = every gate passed (p95 ≤ 15 s, close → scored ≤ 15 s, every attempt within 120 s, no errors on `fake`).

```bash
# fake profile, with the fake provider's limit set to the real provider's (BFL: 24)
SPRING_PROFILES_ACTIVE=fake FAKE_MAX_CONCURRENCY=24 FAKE_LATENCY_MS=3000 ./mvnw spring-boot:run
PB_ADMIN_TOKEN_MDE=<token> PB_ADMIN_TOKEN_BOG=<token> scripts/loadtest.sh --reset            # realistic arrivals
PB_ADMIN_TOKEN_MDE=<token> PB_ADMIN_TOKEN_BOG=<token> scripts/loadtest.sh --reset --ramp 0   # everyone at once
# real providers (production, before doors open): at most 40 generations, then reset the event
PB_ADMIN_TOKEN_MDE=<token> PB_ADMIN_TOKEN_BOG=<token> scripts/loadtest.sh --real --base-url https://<api>
```

`--reset` deletes every city's data. To mirror a two-provider split on `fake`, set `FAKE_IMAGE_PROVIDER_CHAIN=fake,fake2`, `FAKE_IMAGE_LOAD_SPLIT=fake,fake2` and `FAKE2_MAX_CONCURRENCY`. `scripts/loadtest.sh --help` lists every option.

## Providers
- **Images:** fal.ai through its queue API (`FAL_KEY`) and Black Forest Labs (`BFL_API_KEY`), same model (FLUX.2 [klein] 9B). `IMAGE_PROVIDER_CHAIN` is the failover order; with `IMAGE_LOAD_SPLIT` each job goes to the active provider and overflows to the other listed one when its slots (`FAL_MAX_CONCURRENCY`, `BFL_MAX_CONCURRENCY`) are full. A provider without a key is skipped.
- **Failover:** 3 failures in 30 s open a provider's circuit for 60 s and switch the active provider for both cities; either admin can force a provider (`POST /api/admin/providers/active`). Safety blocks never count.
- **Embeddings:** Gemini (`GEMINI_API_KEY`, free tier, client-side RPM/TPM/RPD limits) with Cohere as optional backup. A city run is scored with one provider; if it fails the whole run is re-embedded with the next one, or it stays `SCORING_FAILED` until an admin rescores.
- **Commentary:** Claude (`ANTHROPIC_API_KEY`, `COMMENTARY_MODEL`, default `claude-sonnet-5-5`) through Spring AI. One call per podium step returns Spanish and English (`{es, en}`), 8 s per call, no retries; it is written right after scoring and at `podium/start`, so a reveal never waits. Any failure gets a canned comment for the score band (`commentary/fallback-*.txt`) and the alert `COMMENTARY_FALLBACK`. Without a key every comment is canned.
- **Moderation:** a Spanish + English blocklist (`moderation/blocklist.txt`) checks nicknames and prompts after normalizing case, accents, leetspeak and repeated letters. Optional, with the same model: `PROMPT_LLM_MODERATION=true` checks each prompt before generation, `PROMPT_TRANSLATE_TO_EN=true` generates its English translation (shown to admins as `promptEn`).
- **Probe:** `POST /api/admin/providers/probe` (spends real credits) generates one image per provider, embeds a test image twice (determinism check), asks for one comment and reports latency and errors.
- **Results:** `GET /api/admin/results.csv` (own city). `POST /api/admin/event/reset` with `{"confirm":"RESET"}` deletes every city's data, the snapshot and all images.

## Configuration
Every setting is an environment variable (see `.env.example` and `docs/SPEC.md` §11.1). Locally they are read from `./.env` (UTF-8, `KEY=value`, the `docker compose` `env_file` format); a real environment variable, as on Railway, overrides the same key in `.env`. Tests never read `.env`. Required: `ADMIN_TOKEN_<CITY>` for each city in `CITIES` (at least 24 characters, all different). The API refuses to start if a configured model ID is listed in `src/main/resources/forbidden-models.txt`.

## Deploy notes
- **Exactly one instance** (replicas = 1, serverless off) with a persistent volume at `DATA_DIR`. A second instance would split the game state.
- Health check: `/actuator/health`.
- Never commit secrets: this repository is public. Keys live in the hosting dashboard and GitHub Actions secrets.

## Status
Phases A1 (game core), A2 (real providers, failover, scoring, probe), A3 (commentary, moderation, CSV export, event reset, Dockerfile) and I2 (load test, review fixes). Frozen at `v1.0-freeze`; event-day operation is in `docs/RUNBOOK.md` in the parent repo.
