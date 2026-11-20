package io.github.ewcastroh.promptbattle.game;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single serialized path for every state access (SPEC §7.4). Commands and reads run one at a time on
 * one thread; after each command its effects go to the {@link EffectSink} on the same thread, even when
 * the command failed after mutating state.
 */
public final class GameEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameEngine.class);

    private final Game game;
    private final EffectSink sink;
    private final ExecutorService executor;
    private volatile Thread engineThread;

    public GameEngine(Game game, EffectSink sink) {
        this.game = game;
        this.sink = sink;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "game-engine");
            t.setDaemon(true);
            engineThread = t;
            return t;
        });
    }

    /** Runs a command and waits for its result. Rule violations are rethrown as {@link GameException}. */
    public <T> T execute(Function<Game, T> command) {
        if (Thread.currentThread() == engineThread) {
            return apply(command);
        }
        Future<T> future = executor.submit(() -> apply(command));
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for the game engine", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    public void run(Consumer<Game> command) {
        execute(g -> {
            command.accept(g);
            return null;
        });
    }

    /** Submits a command without waiting (callbacks, timers). Failures are logged. */
    public void post(Consumer<Game> command) {
        if (executor.isShutdown()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    apply(g -> {
                        command.accept(g);
                        return null;
                    });
                } catch (GameException e) {
                    log.debug("Posted command rejected: {}", e.code());
                } catch (RuntimeException e) {
                    log.error("Posted command failed", e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("Engine stopped; command dropped");
        }
    }

    /** Read-only query; serialized with commands so it always sees a consistent state. */
    public <T> T read(Function<GameViews, T> query) {
        return execute(g -> query.apply(g.views()));
    }

    private <T> T apply(Function<Game, T> command) {
        try {
            return command.apply(game);
        } finally {
            List<Effect> effects = game.drainEffects();
            if (!effects.isEmpty()) {
                try {
                    sink.accept(game.state(), effects);
                } catch (RuntimeException e) {
                    log.error("Effect sink failed", e);
                }
            }
        }
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
