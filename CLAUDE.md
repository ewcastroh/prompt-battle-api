# prompt-battle-api

Game server for Prompt Battle (two cities, ~50 players each). Java 25, Spring Boot 4.2.0, Maven wrapper.
Specs live in the parent repo (`prompt-battle-kit`): `docs/SPEC.md`, `docs/API.md` (the contract), `docs/PLAN.md`, `docs/DECISIONS.md`. When this repo is cloned alone, read them on GitHub.

## Commands
- Full verification (what CI runs): `./mvnw -B verify`
- One test class: `./mvnw test -Dtest=RankingTest`
- Run locally, offline: `cp .env.example .env`, set both `ADMIN_TOKEN_*` (24+ chars), then `./mvnw spring-boot:run` → http://localhost:8080/actuator/health. Config is read from `./.env` (`DotenvPropertySourceLoader` via `spring.config.import`); never export variables, never read or commit `.env`. Real env vars override it; surefire points `DOTENV_FILE` away so tests ignore it.

## Layout (`io.github.ewcastroh.promptbattle`)
- `game`: pure domain, **no Spring imports**. `Game` holds every rule and mutation; `GameEngine` runs commands and reads one at a time on one thread; commands return `Effect`s for async work. `GameViews` builds the API read models (`Views`).
- `city`: `CityCatalog` (`CITIES`), `AdminTokens` (`ADMIN_TOKEN_<CODE>`), `GameRuntime` (executes effects, timers, snapshots).
- `generation`: `ImageGenerator` port, fal/BFL/fake adapters, `GenerationDispatcher` (shared FIFO + per-provider slots + load split), `CircuitBreaker`, `ProviderRegistry` (circuits and stats; the active provider is decided in `Game`), `ProviderHttp`/`RetryPolicy`/`Deadline` (adapter HTTP).
- `scoring`: `EmbeddingProvider` port, Gemini/Cohere/fake adapters, `EmbeddingRateLimiter` (Gemini free tier), `EmbeddingService` (cache, averaging), `RunScorer` (one provider per city run, failover re-embeds the run), `DeterminismCheck`.
- `commentary`, `moderation`: ports with canned/blocklist implementations (A3 adds the AI ones).
- `persistence`: `SnapshotCodec` (Jackson 3, field access), `SnapshotStore`, `ImageStore` (`DATA_DIR`).
- `web`, `admin`: REST controllers, ProblemDetail errors, CORS, ETag, rate limits, admin auth.
- `config`: `AppProperties` (every env var), `ForbiddenModelsGuard`, wiring.

## Rules
- Every state change goes through `GameEngine` → `Game`. Controllers, timers and provider callbacks never touch state directly.
- One city's actions never change the other city's state (`CityIsolationTest` compares snapshot JSON).
- Errors: throw `GameException(ErrorCode, params)`; the handler turns it into a ProblemDetail with `code`. Never return user-facing text.
- Model IDs only in `application.yml` + env vars; never one listed in `src/main/resources/forbidden-models.txt`.
- Tests never call real providers: profile `fake` (set in `src/test/resources/config/application.yml`), WireMock (`wiremock-standalone`) for adapters. The real adapters exist only outside `fake` and only for providers with a key (`ProvidersConfig`).
- Concurrency: only the dispatcher thread starts generations; a provider slot is held until the adapter returns. Never call `GameEngine.execute` while holding a dispatcher or breaker lock; listeners only `post`.
- Fake embeddings: a prompt with `[sim=0.83]` scores exactly 83.0 against a target generated from a prompt containing `[target]` (or any uploaded target). `[fail]`, `[unsafe]` and `[delay=ms]` drive the fake image provider.
- Never log API keys, player or admin tokens, or prompt text above DEBUG. Players never upload images.
- One instance only; in-memory state with a snapshot in `DATA_DIR`. No database, Redis or broker.
- Ask before adding a dependency. Latest stable versions, pinned; never upgrade implicitly.
- Commits: `<phase>: <summary>` on behalf of the owner, without AI attribution trailers.
