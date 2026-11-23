/*
 * Prompt Battle load test (SPEC §13, §14.3; PLAN I2). Single-file Java program: `java scripts/LoadTest.java`
 * (or scripts/loadtest.sh). JDK only, no dependencies. Run it against a running API.
 *
 * What it does, against every city of GET /api/cities at the same time:
 *   1. creates one scored round (shared definition) and waits for its calibration;
 *   2. starts it in every city; joins N virtual players per city;
 *   3. each player writes A prompts one after another (arrival spread over --ramp seconds, then a think time
 *      between attempts) and polls GET /api/me every 1.5 s with If-None-Match, like a phone; stages poll every
 *      1.5 s and admins every 2 s;
 *   4. closes the round in every city at once and measures close -> ROUND_SCORED;
 *   5. prints p50/p95/max of "Generate" -> image visible and the errors, per city, and checks the gates.
 *
 * --real caps the total real generations (target + calibration anchors + attempts) at 40.
 * Admin tokens come from the environment: PB_ADMIN_TOKEN_<CITY> (e.g. PB_ADMIN_TOKEN_MDE). Tokens and prompts are
 * never printed.
 */

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LoadTest {

    static final int REAL_CAP = 40;
    static final long POLL_MS = 1500;
    static final long ADMIN_POLL_MS = 2000;
    static final double GATE_P95_MS = 15_000;
    static final double GATE_SCORED_MS = 15_000;

    /** Benign prompts for --real (no fake markers reach a real model). */
    static final String[] REAL_PROMPTS = {
        "a steaming cup of coffee on a wooden table at dawn, watercolor",
        "a yellow taxi on a rainy street at night, neon reflections",
        "a hummingbird drinking from a red flower, macro photo",
        "a colorful bus climbing a green mountain road",
        "a bowl of fruit on a sunny windowsill, oil painting",
        "a small blue house beside a river, morning fog",
        "a cat sleeping on a pile of books, warm light",
        "a hot air balloon over a valley at sunset",
        "a bicycle leaning on a painted wall, flowers in pots",
        "an old cable car above a city, clear sky"};

    // ---- options

    String baseUrl = "http://localhost:8080";
    int players = 50;
    int attempts = 3;
    int windowSec = 120;
    int rampSec = 30;
    int thinkMinSec = 5;
    int thinkMaxSec = 20;
    int anchors = 6;
    boolean real;
    boolean reset;
    boolean playersSet;

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).executor(Executors.newVirtualThreadPerTaskExecutor()).build();
    final String runId = Long.toString(System.currentTimeMillis() % 1_679_616, 36).toUpperCase(Locale.ROOT);
    final Map<String, String> adminTokens = new LinkedHashMap<>();
    final AtomicBoolean stop = new AtomicBoolean();
    final AtomicInteger realGenerations = new AtomicInteger();

    /** Per-city results. */
    static final class CityStats {
        final String code;
        final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        final Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger notModified = new AtomicInteger();
        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger maxQueuePosition = new AtomicInteger();
        final AtomicInteger queuedWithoutPosition = new AtomicInteger();
        final AtomicLong lastDoneAt = new AtomicLong();
        volatile long scoredMs = -1;
        volatile String finalPhase = "?";

        CityStats(String code) {
            this.code = code;
        }

        void error(String kind) {
            errors.computeIfAbsent(kind, k -> new AtomicInteger()).incrementAndGet();
        }

        int errorCount() {
            return errors.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }

    record Reply(int status, String body, String etag) {
        Object json() {
            return Json.parse(body);
        }
    }

    public static void main(String[] args) throws Exception {
        LoadTest t = new LoadTest();
        if (!t.parse(args)) {
            System.exit(2);
        }
        System.exit(t.run() ? 0 : 1);
    }

    boolean parse(String[] args) {
        try {
            return parseOrThrow(args);
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            System.err.println("Missing or invalid option value");
            usage();
            return false;
        }
    }

    private boolean parseOrThrow(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--base-url" -> baseUrl = args[++i].replaceAll("/+$", "");
                case "--players" -> { players = Integer.parseInt(args[++i]); playersSet = true; }
                case "--attempts" -> attempts = Integer.parseInt(args[++i]);
                case "--window" -> windowSec = Integer.parseInt(args[++i]);
                case "--ramp" -> rampSec = Integer.parseInt(args[++i]);
                case "--think" -> {
                    String[] range = args[++i].split("-");
                    thinkMinSec = Integer.parseInt(range[0]);
                    thinkMaxSec = Integer.parseInt(range[range.length - 1]);
                }
                case "--anchors" -> anchors = Integer.parseInt(args[++i]);
                case "--real" -> real = true;
                case "--reset" -> reset = true;
                case "-h", "--help" -> {
                    usage();
                    return false;
                }
                default -> {
                    System.err.println("Unknown option: " + a);
                    usage();
                    return false;
                }
            }
        }
        if (thinkMaxSec < thinkMinSec || rampSec < 0 || attempts < 1 || players < 1) {
            System.err.println("Invalid option values");
            return false;
        }
        return true;
    }

    static void usage() {
        System.err.println("""
                Usage: scripts/loadtest.sh [options]
                  --base-url URL   API origin (default http://localhost:8080)
                  --players N      virtual players per city (default 50; with --real, derived from the cap)
                  --attempts N     attempts per player (default 3)
                  --window S       seconds for every attempt to finish (default 120)
                  --ramp S         first "Generate" of each player spread over S seconds (default 30; 0 = burst)
                  --think A-B      seconds between a player's result and its next attempt (default 5-20)
                  --anchors N      calibration anchors on the server (CALIBRATION_ANCHORS, default 6), for --real
                  --real           real providers: at most 40 generations in total (target + anchors + attempts)
                  --reset          POST /api/admin/event/reset first (deletes EVERY city's data)
                Env: PB_ADMIN_TOKEN_<CITY> for every city (e.g. PB_ADMIN_TOKEN_MDE, PB_ADMIN_TOKEN_BOG).""");
    }

    // ---- the run

    boolean run() throws Exception {
        List<String> cities = new ArrayList<>();
        for (Object c : (List<?>) get("/api/cities", null, null).json()) {
            cities.add((String) ((Map<?, ?>) c).get("code"));
        }
        for (String city : cities) {
            String token = System.getenv("PB_ADMIN_TOKEN_" + city.toUpperCase(Locale.ROOT));
            if (token == null || token.isBlank()) {
                System.err.println("Missing env PB_ADMIN_TOKEN_" + city.toUpperCase(Locale.ROOT));
                return false;
            }
            adminTokens.put(city, token);
        }
        if (real) {
            int budget = REAL_CAP - 1 - anchors;
            int perCity = budget / (cities.size() * attempts);
            if (perCity < 1) {
                System.err.println("--real: the cap of " + REAL_CAP + " leaves no attempts");
                return false;
            }
            if (!playersSet || players > perCity) {
                players = perCity;
            }
        }
        int planned = (real ? 1 + anchors : 0) + cities.size() * players * attempts;
        System.out.printf(Locale.ROOT, "Load test %s against %s (%s): %d cities x %d players x %d attempts, window %d s,"
                + " ramp %d s, think %d-%d s%s%n", runId, baseUrl, real ? "REAL providers" : "fake providers",
                cities.size(), players, attempts, windowSec, rampSec, thinkMinSec, thinkMaxSec,
                real ? ", planned generations " + planned + " (cap " + REAL_CAP + ")" : "");
        if (real && planned > REAL_CAP) {
            System.err.println("--real: planned generations exceed the cap");
            return false;
        }

        String first = cities.getFirst();
        if (reset) {
            expect(post("/api/admin/event/reset", "{\"confirm\":\"RESET\"}", admin(first)), 204, "event reset");
            System.out.println("Event reset.");
        }
        for (String city : cities) {
            Map<?, ?> state = (Map<?, ?>) expect(get("/api/admin/state", admin(city), null), 200, "admin state").json();
            String phase = (String) state.get("phase");
            if (!List.of("LOBBY", "ROUND_SCORED", "MINI_PODIUM").contains(phase)) {
                System.err.println(city + " is in " + phase + ": a round can't start (use --reset on a test instance)");
                return false;
            }
        }

        // What this run measures: the server's image providers and their limits (SPEC §14.3: the fake limit must be
        // the real one).
        Map<?, ?> providers = (Map<?, ?>) expect(get("/api/admin/providers", admin(first), null), 200, "providers").json();
        StringBuilder limits = new StringBuilder();
        for (Object o : (List<?>) providers.get("image")) {
            Map<?, ?> p = (Map<?, ?>) o;
            Object max = p.get("maxConcurrency");
            limits.append(' ').append(p.get("id")).append('=')
                    .append(max instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(max));
        }
        System.out.println("Server image providers: active " + providers.get("active") + ", chain " + providers.get("chain")
                + ", load split " + providers.get("loadSplit") + ", max concurrency" + limits);

        // 1. One scored round, shared by every city.
        String targetPrompt = real ? "a red bicycle in front of a colorful wall, morning light"
                : "load test target [target]";
        if (real) {
            realGenerations.incrementAndGet();
        }
        Map<?, ?> preview = (Map<?, ?>) expect(post("/api/admin/rounds/generate-target",
                "{\"prompt\":" + Json.quote(targetPrompt) + "}", admin(first)), 200, "generate target").json();
        int duration = Math.min(1800, windowSec + 120);
        String body = "{\"name\":" + Json.quote("Load test " + runId) + ",\"hiddenPrompt\":" + Json.quote(targetPrompt)
                + ",\"durationSec\":" + duration + ",\"attemptsPerPlayer\":" + attempts
                + ",\"scored\":true,\"seed\":null,\"previewId\":" + Json.quote((String) preview.get("previewId")) + "}";
        Map<?, ?> round = (Map<?, ?>) expect(post("/api/admin/rounds", body, admin(first)), 201, "create round").json();
        String roundId = (String) round.get("id");
        long calStart = System.nanoTime();
        Map<?, ?> calibration = awaitCalibration(first, roundId, Duration.ofSeconds(real ? 180 : 60));
        int anchorCount = ((List<?>) calibration.get("anchors")).size();
        if (real) {
            realGenerations.addAndGet(anchorCount);
        }
        System.out.printf(Locale.ROOT, "Round %s ready: calibration %s with %d anchors in %.1f s.%n", roundId,
                calibration.get("status"), anchorCount, (System.nanoTime() - calStart) / 1e9);
        if (!"READY".equals(calibration.get("status"))) {
            System.err.println("Calibration did not become READY");
            return false;
        }
        if (real && 1 + anchorCount + cities.size() * players * attempts > REAL_CAP) {
            System.err.println("--real: the server used " + anchorCount + " anchors (expected " + anchors
                    + "); stopping before any attempt so the cap holds. Re-run with --anchors " + anchorCount);
            return false;
        }

        // 2. Start in every city, join the players.
        Map<String, CityStats> stats = new LinkedHashMap<>();
        for (String city : cities) {
            stats.put(city, new CityStats(city));
            expect(post("/api/admin/rounds/" + roundId + "/start", null, admin(city)), 200, "start " + city);
        }
        Map<String, List<String>> tokens = new LinkedHashMap<>();
        for (String city : cities) {
            List<String> list = new ArrayList<>();
            for (int i = 0; i < players; i++) {
                String nick = "LT" + runId + "_" + city + String.format(Locale.ROOT, "%02d", i);
                Reply r = post("/api/cities/" + city + "/players", "{\"nickname\":" + Json.quote(nick) + "}", null);
                if (r.status() != 201 && r.status() != 200) {
                    stats.get(city).error("join " + r.status() + " " + code(r));
                    continue;
                }
                list.add((String) ((Map<?, ?>) r.json()).get("token"));
            }
            tokens.put(city, list);
        }
        System.out.println("Round started in " + String.join(", ", cities) + "; players joined: "
                + tokens.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().size()).toList());

        // 3. Players, stages and admins.
        long t0 = System.nanoTime();
        long deadline = t0 + Duration.ofSeconds(windowSec).toNanos();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        int total = tokens.values().stream().mapToInt(List::size).sum();
        CountDownLatch finished = new CountDownLatch(total);
        for (String city : cities) {
            CityStats s = stats.get(city);
            List<String> list = tokens.get(city);
            for (int i = 0; i < list.size(); i++) {
                String token = list.get(i);
                int index = i;
                pool.submit(() -> {
                    try {
                        player(s, token, index, t0, deadline, finished);
                    } catch (Exception e) {
                        s.error("player exception " + e.getClass().getSimpleName());
                    }
                });
            }
            pool.submit(() -> poller(s, "/api/cities/" + city + "/stage/state", null, POLL_MS));
            pool.submit(() -> poller(s, "/api/admin/state", admin(city), ADMIN_POLL_MS));
        }
        boolean allFinished = finished.await(windowSec + 60L, java.util.concurrent.TimeUnit.SECONDS);
        double windowUsed = (System.nanoTime() - t0) / 1e9;

        // 4. Close every city at once and time close -> ROUND_SCORED.
        CountDownLatch scored = new CountDownLatch(cities.size());
        for (String city : cities) {
            CityStats s = stats.get(city);
            pool.submit(() -> {
                try {
                    long c0 = System.nanoTime();
                    Reply r = post("/api/admin/rounds/" + roundId + "/close", null, admin(city));
                    if (r.status() != 200) {
                        s.error("close " + r.status() + " " + code(r));
                        return;
                    }
                    long until = c0 + Duration.ofSeconds(real ? 120 : 60).toNanos();
                    while (System.nanoTime() < until) {
                        Map<?, ?> st = (Map<?, ?>) get("/api/admin/state", admin(city), null).json();
                        s.finalPhase = (String) st.get("phase");
                        if ("ROUND_SCORED".equals(s.finalPhase)) {
                            s.scoredMs = (System.nanoTime() - c0) / 1_000_000;
                            return;
                        }
                        Thread.sleep(250);
                    }
                    s.error("not scored in time (phase " + s.finalPhase + ")");
                } catch (Exception e) {
                    s.error("close exception " + e.getClass().getSimpleName());
                } finally {
                    scored.countDown();
                }
            });
        }
        scored.await();
        stop.set(true);
        pool.shutdown();
        pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        // 5. Report and gates.
        return report(stats, allFinished, windowUsed, t0);
    }

    /**
     * One phone: polls GET /api/me every 1.5 s (If-None-Match) from the start until the run ends, like a phone left
     * open; presses "Generate" at a random time within --ramp, then a think time after each result; selects its
     * first image as entry.
     * {@code finished} counts down once its attempts are over, while it keeps polling.
     */
    void player(CityStats s, String token, int index, long t0, long deadline, CountDownLatch finished)
            throws Exception {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        String etag = null;
        boolean selected = false;
        boolean over = false;
        int k = 0;
        String attemptId = null;
        long a0 = 0;
        long nextAttemptAt = t0 + (long) (rnd.nextDouble() * rampSec * 1e9); // polls from t0, like an open phone
        try {
            while (!stop.get()) {
                if (!over && attemptId == null && System.nanoTime() >= nextAttemptAt) {
                    String prompt = real ? REAL_PROMPTS[(index + k) % REAL_PROMPTS.length]
                            : String.format(Locale.ROOT, "load %s %d %d [sim=0.%02d]", s.code, index, k,
                                    30 + rnd.nextInt(65));
                    if (real && realGenerations.incrementAndGet() > REAL_CAP) {
                        s.error("real cap reached");
                        over = true;
                        finished.countDown();
                        continue;
                    }
                    a0 = System.nanoTime();
                    Reply r = post("/api/rounds/current/attempts", "{\"prompt\":" + Json.quote(prompt) + "}",
                            bearer(token));
                    s.requests.incrementAndGet();
                    if (r.status() != 202) {
                        s.error("attempt " + r.status() + " " + code(r));
                        over = true;
                        finished.countDown();
                        continue;
                    }
                    attemptId = (String) ((Map<?, ?>) r.json()).get("attemptId");
                }
                Thread.sleep(POLL_MS);
                Reply me = get("/api/me", bearer(token), etag);
                s.requests.incrementAndGet();
                if (me.status() == 304) {
                    s.notModified.incrementAndGet();
                    continue;
                }
                if (me.status() != 200) {
                    s.error("me " + me.status() + " " + code(me));
                    continue;
                }
                etag = me.etag();
                Map<?, ?> attempt = attemptId == null ? null : find((Map<?, ?>) me.json(), attemptId);
                if (attempt == null) {
                    continue;
                }
                String status = (String) attempt.get("status");
                if ("QUEUED".equals(status)) {
                    Object pos = attempt.get("queuePosition");
                    if (pos instanceof Number n && n.intValue() >= 1) {
                        s.maxQueuePosition.accumulateAndGet(n.intValue(), Math::max);
                    } else {
                        s.queuedWithoutPosition.incrementAndGet();
                    }
                    continue;
                }
                if ("GENERATING".equals(status)) {
                    continue;
                }
                long ms = (System.nanoTime() - a0) / 1_000_000;
                if ("DONE".equals(status)) {
                    s.latencies.add(ms);
                    s.done.incrementAndGet();
                    if (System.nanoTime() > deadline) {
                        s.error("attempt finished after the window");
                    }
                    s.lastDoneAt.accumulateAndGet(System.nanoTime(), Math::max);
                    if (!selected) {
                        Reply sel = post("/api/rounds/current/entry",
                                "{\"attemptId\":" + Json.quote(attemptId) + "}", bearer(token));
                        s.requests.incrementAndGet();
                        if (sel.status() != 204) {
                            s.error("entry " + sel.status() + " " + code(sel));
                        }
                        selected = true;
                    }
                } else {
                    s.error("attempt " + status);
                }
                attemptId = null;
                k++;
                if (k >= attempts) {
                    over = true;
                    finished.countDown();
                } else {
                    nextAttemptAt = System.nanoTime()
                            + (long) (1e9 * (thinkMinSec + rnd.nextDouble() * (thinkMaxSec - thinkMinSec)));
                }
            }
        } finally {
            if (!over) {
                finished.countDown();
            }
        }
    }

    /** A stage (no auth) or admin console polling with If-None-Match until the run ends. */
    void poller(CityStats s, String path, String[] header, long everyMs) {
        String etag = null;
        while (!stop.get()) {
            try {
                Reply r = get(path, header, etag);
                s.requests.incrementAndGet();
                if (r.status() == 304) {
                    s.notModified.incrementAndGet();
                } else if (r.status() == 200) {
                    etag = r.etag();
                } else {
                    s.error("poll " + path + " " + r.status() + " " + code(r));
                }
                Thread.sleep(everyMs);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                s.error("poll exception " + e.getClass().getSimpleName());
            }
        }
    }

    boolean report(Map<String, CityStats> stats, boolean allFinished, double windowUsed, long t0) {
        System.out.println();
        System.out.printf(Locale.ROOT, "%-5s %7s %8s %8s %8s %10s %8s %9s %10s %7s%n", "city", "done", "p50 s", "p95 s",
                "max s", "last at s", "maxQpos", "requests", "close->sc", "errors");
        boolean ok = allFinished;
        List<Long> all = new ArrayList<>();
        for (CityStats s : stats.values()) {
            List<Long> l;
            synchronized (s.latencies) {
                l = new ArrayList<>(s.latencies);
            }
            Collections.sort(l);
            all.addAll(l);
            int expected = players * attempts;
            double lastAt = s.lastDoneAt.get() == 0 ? 0 : (s.lastDoneAt.get() - t0) / 1e9;
            System.out.printf(Locale.ROOT, "%-5s %3d/%-3d %8.2f %8.2f %8.2f %10.1f %8d %9d %10s %7d%n", s.code,
                    s.done.get(), expected, pct(l, 50) / 1000, pct(l, 95) / 1000, max(l) / 1000, lastAt,
                    s.maxQueuePosition.get(), s.requests.get(),
                    s.scoredMs < 0 ? "-" : String.format(Locale.ROOT, "%.2f s", s.scoredMs / 1000.0), s.errorCount());
            s.errors.forEach((k, v) -> System.out.printf("        error: %s x%d%n", k, v.get()));
            if (s.queuedWithoutPosition.get() > 0) {
                System.out.printf("        note: QUEUED without a queue position in %d polls (job leaving the FIFO)%n",
                        s.queuedWithoutPosition.get());
            }
            boolean cityOk = s.done.get() == expected && lastAt <= windowSec && pct(l, 95) <= GATE_P95_MS
                    && s.scoredMs >= 0 && s.scoredMs <= GATE_SCORED_MS
                    && (real || s.errorCount() == 0);
            ok &= cityOk;
        }
        Collections.sort(all);
        int totalRequests = stats.values().stream().mapToInt(x -> x.requests.get()).sum();
        System.out.printf(Locale.ROOT, "all   p50 %.2f s, p95 %.2f s, max %.2f s; %d requests in %.0f s (%.1f req/s)%n",
                pct(all, 50) / 1000, pct(all, 95) / 1000, max(all) / 1000, totalRequests, windowUsed,
                totalRequests / Math.max(1, windowUsed));
        if (real) {
            System.out.println("Real generations requested: " + realGenerations.get() + " (cap " + REAL_CAP + ")");
        }
        System.out.printf(Locale.ROOT, "Gates (SPEC §13): every attempt done within %d s, p95 <= %.0f s, close -> scored"
                + " <= %.0f s%s: %s%n", windowSec, GATE_P95_MS / 1000, GATE_SCORED_MS / 1000,
                real ? "" : ", no errors", ok ? "PASS" : "FAIL");
        return ok;
    }

    // ---- helpers

    Map<?, ?> awaitCalibration(String city, String roundId, Duration timeout) throws Exception {
        long until = System.nanoTime() + timeout.toNanos();
        while (true) {
            for (Object o : (List<?>) expect(get("/api/admin/rounds", admin(city), null), 200, "rounds").json()) {
                Map<?, ?> r = (Map<?, ?>) o;
                if (roundId.equals(r.get("id"))) {
                    Map<?, ?> cal = (Map<?, ?>) r.get("calibration");
                    String st = (String) cal.get("status");
                    if (!"PENDING".equals(st) || System.nanoTime() > until) {
                        return cal;
                    }
                }
            }
            if (System.nanoTime() > until) {
                throw new IllegalStateException("round " + roundId + " is gone (event reset or restart?)");
            }
            Thread.sleep(500);
        }
    }

    static Map<?, ?> find(Map<?, ?> me, String attemptId) {
        Object list = me.get("attempts");
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && attemptId.equals(m.get("id"))) {
                    return m;
                }
            }
        }
        return null;
    }

    static double pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    static double max(List<Long> sorted) {
        return sorted.isEmpty() ? 0 : sorted.getLast();
    }

    static void sleepUntil(long nanoTime) throws InterruptedException {
        long ms = (nanoTime - System.nanoTime()) / 1_000_000;
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    String[] admin(String city) {
        return new String[] {"X-Admin-Token", adminTokens.get(city)};
    }

    static String[] bearer(String token) {
        return new String[] {"Authorization", "Bearer " + token};
    }

    static String code(Reply r) {
        try {
            if (r.json() instanceof Map<?, ?> m && m.get("code") != null) {
                return String.valueOf(m.get("code"));
            }
        } catch (RuntimeException ignored) {
            // not JSON
        }
        return "";
    }

    static Reply expect(Reply r, int status, String what) {
        if (r.status() != status) {
            throw new IllegalStateException(what + ": HTTP " + r.status() + " " + code(r));
        }
        return r;
    }

    Reply get(String path, String[] header, String etag) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30)).GET();
        if (header != null) {
            b.header(header[0], header[1]);
        }
        if (etag != null) {
            b.header("If-None-Match", etag);
        }
        return send(b.build());
    }

    Reply post(String path, String json, String[] header) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (header != null) {
            b.header(header[0], header[1]);
        }
        return send(b.build());
    }

    Reply send(HttpRequest request) throws Exception {
        HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body(), r.headers().firstValue("ETag").orElse(null));
    }

    /** A minimal JSON reader/writer (objects, arrays, strings, numbers, booleans, null). */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json j = new Json(text == null ? "" : text);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != j.s.length()) {
                throw new IllegalArgumentException("trailing JSON at " + j.i);
            }
            return v;
        }

        static String quote(String v) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : v.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                    }
                }
            }
            return b.append('"').toString();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private Object value() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // ':'
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
            }
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return b.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 'r' -> b.append('\r');
                        case 't' -> b.append('\t');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> {
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        private Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw new IllegalArgumentException("bad JSON literal at " + i);
            }
            i += word.length();
            return v;
        }

        private Number number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw new IllegalArgumentException("bad JSON at " + i);
            }
            String n = s.substring(start, i);
            return n.contains(".") || n.contains("e") || n.contains("E") ? Double.parseDouble(n) : Long.parseLong(n);
        }
    }
}
