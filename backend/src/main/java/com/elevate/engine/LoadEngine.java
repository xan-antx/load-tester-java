package com.elevate.engine;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Our own load generator: one instance runs one load profile against one target.
 *
 * <p>How it works:
 * <ol>
 *   <li>A scheduler thread ticks every 100 ms. Each tick starts new virtual users until the
 *       profile's target is reached, at most {@code spawnRate} per second, and ends the run
 *       once the profile's duration has passed.</li>
 *   <li>Every user is a {@link VirtualUser} on its own virtual thread
 *       ({@link Executors#newVirtualThreadPerTaskExecutor()}), sending requests with one
 *       shared {@link HttpClient}.</li>
 *   <li>Once a second the {@link IntervalSampler} drains the latency recorders into the
 *       timeline (and, for capacity runs, into the current step).</li>
 *   <li>{@link #run()} blocks until the run ends — duration over, {@link #stop()}, or an
 *       early stop — then stops the users and returns the measurements.</li>
 * </ol>
 *
 * <p>Stopping cleanly: {@link #stop()} may be called from any thread. It sets the volatile
 * {@code running} flag and releases the latch {@code run()} waits on; {@code run()} then
 * shuts the scheduler down, interrupts every user ({@link ExecutorService#shutdownNow()})
 * and waits for all of them to finish, so no thread outlives the engine.
 */
public final class LoadEngine {

    private static final long TICK_MILLIS = 100;
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);

    private final EngineSpec spec;
    private final RunRecorder recorder;
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<StopReason> stopReason = new AtomicReference<>();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicBoolean used = new AtomicBoolean();
    private volatile boolean running;
    private volatile long startNanos;

    // Touched only by the scheduler thread.
    private int startedUsers;
    private int rampTarget;
    private int rampStartUsers;
    private double rampStartSeconds;

    /** @param spec what to run */
    public LoadEngine(EngineSpec spec) {
        this.spec = spec;
        this.recorder = new RunRecorder(spec.templates());
    }

    /**
     * Runs the load test on the calling thread and blocks until it ends.
     *
     * @return the measurements
     * @throws IllegalStateException if called twice, or if the engine itself failed
     */
    public EngineResult run() {
        if (!used.compareAndSet(false, true)) {
            throw new IllegalStateException("a LoadEngine runs only once");
        }
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(spec.requestTimeout())
                .build();
        List<HttpRequest> requests = spec.templates().stream().map(this::prepare).toList();
        ExecutorService users = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("engine-scheduler").factory());

        StepTracker steps = spec.profile() instanceof LoadProfile.Steps plan
                ? new StepTracker(plan, spec.stepJudge(), () -> finish(StopReason.EARLY_STOP)) : null;
        IntervalSampler sampler = new IntervalSampler(recorder, spec.clock(), this::elapsedSeconds, steps);

        startNanos = System.nanoTime();
        running = stopReason.get() == null;
        scheduler.scheduleAtFixedRate(guarded(() -> spawnTick(client, requests, users)), 0, TICK_MILLIS,
                TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(guarded(sampler::sample), 1, 1, TimeUnit.SECONDS);

        boolean interrupted = awaitEnd();
        scheduler.shutdown();
        interrupted |= awaitQuietly(scheduler);
        running = false;
        users.shutdownNow();
        interrupted |= awaitQuietly(users);
        client.shutdownNow();
        EngineResult result = new EngineResult(sampler.finish(), steps == null ? List.of() : steps.records(),
                stopReason.get());
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure.get() != null) {
            throw new IllegalStateException("the load engine failed: " + failure.get().getMessage(), failure.get());
        }
        return result;
    }

    /** Asks the run to end now (cancel). Safe from any thread, also before {@link #run()}. */
    public void stop() {
        finish(StopReason.CANCELLED);
    }

    /** Whether the engine is generating load. */
    public boolean isRunning() {
        return running;
    }

    private void finish(StopReason reason) {
        if (stopReason.compareAndSet(null, reason)) {
            running = false;
            done.countDown();
        }
    }

    private void spawnTick(HttpClient client, List<HttpRequest> requests, ExecutorService users) {
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        if (elapsed.compareTo(spec.profile().totalDuration()) >= 0) {
            finish(StopReason.COMPLETED);
            return;
        }
        double seconds = elapsed.toNanos() / 1e9;
        int target = spec.profile().targetUsers(elapsed);
        if (target != rampTarget) {
            rampTarget = target;
            rampStartUsers = startedUsers;
            rampStartSeconds = seconds;
        }
        long allowed = Math.min(rampTarget,
                rampStartUsers + 1 + (long) Math.floor((seconds - rampStartSeconds) * spec.profile().spawnRate()));
        while (startedUsers < allowed && running) {
            users.execute(new VirtualUser(client, requests, recorder, spec.thinkTime(), this::isRunning,
                    ThreadLocalRandom.current().nextInt(requests.size())));
            startedUsers++;
        }
    }

    private HttpRequest prepare(RequestTemplate template) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(template.uri()).timeout(spec.requestTimeout());
        if (template.body() != null) {
            builder.header("Content-Type", "application/json")
                    .method(template.method(), HttpRequest.BodyPublishers.ofString(template.body()));
        } else {
            builder.method(template.method(), HttpRequest.BodyPublishers.noBody());
        }
        spec.headers().values().forEach(builder::header);
        return builder.build();
    }

    private double elapsedSeconds() {
        return (System.nanoTime() - startNanos) / 1e9;
    }

    /** A failing scheduled task would silently stop repeating; record the failure and end the run instead. */
    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                failure.compareAndSet(null, e);
                finish(StopReason.CANCELLED);
            }
        };
    }

    /** Waits for the end of the run; an interrupt of the waiting thread cancels the run. */
    private boolean awaitEnd() {
        try {
            done.await();
            return false;
        } catch (InterruptedException e) {
            finish(StopReason.CANCELLED);
            return true;
        }
    }

    /** Waits for an executor to terminate; returns whether the wait was interrupted. */
    private static boolean awaitQuietly(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("engine threads did not stop within " + SHUTDOWN_WAIT);
            }
            return false;
        } catch (InterruptedException e) {
            executor.shutdownNow();
            return true;
        }
    }
}
