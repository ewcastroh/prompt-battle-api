package io.github.ewcastroh.promptbattle.generation;

import io.github.ewcastroh.promptbattle.game.FailureKind;
import io.github.ewcastroh.promptbattle.game.QueuePositions;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One FIFO shared by both cities (SPEC §5.5) with a concurrency limit per provider. A1 dispatcher: strict
 * FIFO, the head job waits for a slot on its provider. A2 replaces dispatching with load split.
 */
public final class GenerationQueue implements QueuePositions, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GenerationQueue.class);

    private record Job(String id, String providerId, GenerationRequest request, Runnable onStart,
            CompletableFuture<GenerationResult> result) {
    }

    private final Map<String, ImageGenerator> generators = new LinkedHashMap<>();
    private final Map<String, Semaphore> slots = new LinkedHashMap<>();
    private final LinkedHashMap<String, Job> pending = new LinkedHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final ExecutorService runners = Executors.newVirtualThreadPerTaskExecutor();
    private final Thread dispatcher;
    private volatile boolean running = true;

    public GenerationQueue(List<ImageGenerator> generators) {
        for (ImageGenerator g : generators) {
            this.generators.put(g.providerId(), g);
            this.slots.put(g.providerId(), new Semaphore(Math.max(1, g.maxConcurrency()), true));
        }
        dispatcher = Thread.ofPlatform().daemon().name("generation-dispatcher").start(this::dispatch);
    }

    public CompletableFuture<GenerationResult> submit(String jobId, String providerId, GenerationRequest request,
            Runnable onStart) {
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        if (!generators.containsKey(providerId)) {
            result.complete(new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "Unknown provider " + providerId));
            return result;
        }
        lock.lock();
        try {
            pending.put(jobId, new Job(jobId, providerId, request, onStart, result));
            notEmpty.signalAll();
        } finally {
            lock.unlock();
        }
        return result;
    }

    /** Removes a job that has not started; its future completes with a failure. */
    public boolean cancel(String jobId) {
        Job job;
        lock.lock();
        try {
            job = pending.remove(jobId);
        } finally {
            lock.unlock();
        }
        if (job != null) {
            job.result().complete(new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "cancelled"));
        }
        return job != null;
    }

    public void cancelAll() {
        lock.lock();
        List<Job> jobs;
        try {
            jobs = List.copyOf(pending.values());
            pending.clear();
        } finally {
            lock.unlock();
        }
        jobs.forEach(j -> j.result().complete(new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, "cancelled")));
    }

    @Override
    public Integer positionOf(String jobId) {
        lock.lock();
        try {
            int position = 1;
            for (String id : pending.keySet()) {
                if (id.equals(jobId)) {
                    return position;
                }
                position++;
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    public int length() {
        lock.lock();
        try {
            return pending.size();
        } finally {
            lock.unlock();
        }
    }

    public Map<String, ImageGenerator> generators() {
        return generators;
    }

    private void dispatch() {
        while (running) {
            try {
                Job head = awaitHead();
                Semaphore slot = slots.get(head.providerId());
                slot.acquire();
                Job job;
                lock.lock();
                try {
                    job = pending.remove(head.id());
                } finally {
                    lock.unlock();
                }
                if (job == null) {
                    slot.release(); // cancelled while waiting for a slot
                    continue;
                }
                runners.execute(() -> run(job, slot));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Job awaitHead() throws InterruptedException {
        lock.lock();
        try {
            while (pending.isEmpty()) {
                notEmpty.await();
            }
            Iterator<Job> it = pending.values().iterator();
            return it.next();
        } finally {
            lock.unlock();
        }
    }

    private void run(Job job, Semaphore slot) {
        GenerationResult result;
        try {
            job.onStart().run();
            result = generators.get(job.providerId()).generate(job.request());
        } catch (RuntimeException e) {
            log.warn("Provider {} threw {}", job.providerId(), e.getClass().getSimpleName());
            result = new GenerationResult.Failure(FailureKind.PROVIDER_ERROR, e.getClass().getSimpleName());
        } finally {
            slot.release();
        }
        job.result().complete(result);
    }

    @Override
    public void close() {
        running = false;
        dispatcher.interrupt();
        cancelAll();
        runners.shutdownNow();
    }
}
