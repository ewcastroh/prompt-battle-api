# prompt-battle-api

Game server for **Prompt Battle: ¡Compila tu Imaginación!**, a live game played at the same time in Medellín and Bogotá (EPAM Community Palooza, 2026-12-02). Players write prompts on their phones, the server generates every image with the same model and seed, scores how close each image is to a target, and each city's admin reveals that city's top 5 on the projector.

Part of [prompt-battle-kit](https://github.com/ewcastroh/prompt-battle-kit), which holds the spec, the API contract (`docs/API.md`) and the decisions. The UI is [prompt-battle-ui](https://github.com/ewcastroh/prompt-battle-ui).

## Stack
Java 25 · Spring Boot 4.2.0 · Maven wrapper · ZXing (QR). No database: one instance keeps the whole event in memory and snapshots it to `DATA_DIR`.

## Run locally (no keys needed)

```bash
export SPRING_PROFILES_ACTIVE=fake
export ADMIN_TOKEN_MDE=change-me-medellin-24-chars-min
export ADMIN_TOKEN_BOG=change-me-bogota-24-chars-minimum
./mvnw spring-boot:run
curl -s localhost:8080/actuator/health   # {"status":"UP",...}
curl -s localhost:8080/api/cities
```

The `fake` profile replaces every provider: images are generated locally and scoring is deterministic. To play with known scores, generate the target from a prompt containing `[target]` and write player prompts with `[sim=0.83]` (→ score 83.0).

From the parent folder, `docker compose up --build` runs the API with the UI (after the integration phase).

## Test

```bash
./mvnw -B verify
```

Includes the state machine and its 409s, per-city isolation, attempt accounting, close and grace rules, ranking and tie-breaks, scoring, nickname rules, admin auth, the round lock, snapshot restore, CORS, ETag, the API contract shapes and a full 2 cities × 50 players simulation. Tests never call real providers.

## Configuration
Every setting is an environment variable (see `.env.example` and `docs/SPEC.md` §11.1). Required: `ADMIN_TOKEN_<CITY>` for each city in `CITIES` (at least 24 characters, all different). The API refuses to start if a configured model ID is listed in `src/main/resources/forbidden-models.txt`.

## Deploy notes
- **Exactly one instance** (replicas = 1, serverless off) with a persistent volume at `DATA_DIR`. A second instance would split the game state.
- Health check: `/actuator/health`.
- Never commit secrets: this repository is public. Keys live in the hosting dashboard and GitHub Actions secrets.

## Status
Phase A1 (game core, fake providers). Real image and embedding providers, failover and the probe come in A2; AI commentary, moderation content, CSV export and the Dockerfile in A3.
