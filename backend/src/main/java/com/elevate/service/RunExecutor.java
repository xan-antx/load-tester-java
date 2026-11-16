package com.elevate.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.engine.EngineResult;
import com.elevate.engine.LoadEngine;
import com.elevate.engine.StopReason;
import com.elevate.exception.NotFoundException;
import com.elevate.model.RunStatus;

import jakarta.annotation.PreDestroy;

/**
 * Runs load tests in the background, one virtual thread per run, so start requests return
 * at once (202) and the browser polls the status.
 *
 * <p>A top-level run (single job, split group or series) holds one {@link ActiveRunLimiter}
 * slot from start to end; the {@code finally} block gives it back however the run ends.
 * A split group runs each child's engine on its own virtual thread, in parallel, and waits
 * for all of them. A series runs its repeats one after another and stops at the first that
 * does not complete.
 */
@Service
public class RunExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RunExecutor.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 20;

    private final RunStore store;
    private final RunRegistry registry;
    private final ActiveRunLimiter limiter;
    private final EngineSpecFactory specs;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param store    database writes
     * @param registry running engines and cancel requests
     * @param limiter  the MAX_ACTIVE_RUNS slots
     * @param specs    builds engine specifications
     */
    public RunExecutor(RunStore store, RunRegistry registry, ActiveRunLimiter limiter, EngineSpecFactory specs) {
        this.store = store;
        this.registry = registry;
        this.limiter = limiter;
        this.specs = specs;
    }

    /**
     * Starts a single run or split group in the background. The caller has taken a limiter
     * slot; it is released when the run ends.
     */
    public void launch(PreparedRun run, RequestHeaders headers) {
        threads.execute(() -> {
            try {
                execute(run, headers);
            } finally {
                limiter.release();
            }
        });
    }

    /**
     * Starts a repeat series in the background (the caller has taken a limiter slot).
     *
     * @param seriesId   the stored series
     * @param repeat     number of repeats
     * @param nextRepeat creates and stores the next repeat
     * @param headers    extra request headers
     */
    public void launchSeries(String seriesId, int repeat, Supplier<PreparedRun> nextRepeat, RequestHeaders headers) {
        threads.execute(() -> {
            try {
                executeSeries(seriesId, repeat, nextRepeat, headers);
            } finally {
                limiter.release();
            }
        });
    }

    /**
     * Stops a job, every child of a split group, or a whole series (no further repeats
     * start and the current one is stopped).
     *
     * @param id the run
     * @throws NotFoundException when no run has that id
     */
    public void cancel(String id) {
        RunStore.Kind kind = store.kindOf(id).orElseThrow(() -> new NotFoundException("run '" + id + "' not found"));
        switch (kind) {
            case SERIES -> {
                RunStore.RepeatSeriesState state = store.seriesState(id);
                if (!state.status().isTerminal()) {
                    registry.requestCancel(id);
                    if (!state.runIds().isEmpty()) {
                        cancel(state.runIds().getLast());
                    }
                }
            }
            case GROUP -> store.childIds(id).forEach(this::cancelJob);
            case JOB -> cancelJob(id);
        }
    }

    private void cancelJob(String jobId) {
        RunStatus status = store.statusOf(jobId).orElseThrow();
        if (status.isTerminal()) {
            return;
        }
        registry.requestCancel(jobId);
        if (status == RunStatus.QUEUED) {
            store.changeJobStatus(jobId, RunStatus.CANCELLED, null);
        }
    }

    private void execute(PreparedRun run, RequestHeaders headers) {
        if (!run.isGroup()) {
            executeJob(run.id(), headers);
            return;
        }
        List<Future<?>> children = new ArrayList<>();
        run.childIds().forEach(child -> children.add(threads.submit(() -> executeJob(child, headers))));
        for (Future<?> child : children) {
            try {
                child.get();
            } catch (InterruptedException e) {
                run.childIds().forEach(registry::requestCancel);
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                LOG.error("A child of split run {} failed unexpectedly", run.id(), e.getCause());
            }
        }
        store.refreshGroup(run.id());
    }

    private void executeJob(String jobId, RequestHeaders headers) {
        if (registry.isCancelRequested(jobId)) {
            store.changeJobStatus(jobId, RunStatus.CANCELLED, null);
            return;
        }
        try {
            LoadEngine engine = new LoadEngine(specs.create(store.loadPlan(jobId), headers));
            registry.register(jobId, engine);
            store.changeJobStatus(jobId, RunStatus.RUNNING, null);
            EngineResult result = engine.run();
            if (registry.isShuttingDown()) {
                store.saveResults(jobId, result, RunStatus.INTERRUPTED, RunStore.INTERRUPTED_REASON);
            } else {
                RunStatus status = result.stopReason() == StopReason.CANCELLED ? RunStatus.CANCELLED : RunStatus.COMPLETED;
                store.saveResults(jobId, result, status, null);
            }
        } catch (RuntimeException e) {
            LOG.error("Load test {} failed", jobId, e);
            store.changeJobStatus(jobId, RunStatus.FAILED, headers.redact(String.valueOf(e.getMessage())));
        } finally {
            registry.unregister(jobId);
        }
    }

    private void executeSeries(String seriesId, int repeat, Supplier<PreparedRun> nextRepeat, RequestHeaders headers) {
        List<RunStatus> statuses = new ArrayList<>();
        try {
            store.changeSeriesStatus(seriesId, RunStatus.RUNNING, null);
            for (int i = 0; i < repeat && !registry.isCancelRequested(seriesId) && !registry.isShuttingDown(); i++) {
                PreparedRun run = nextRepeat.get();
                store.addSeriesRun(seriesId, run.id());
                if (registry.isCancelRequested(seriesId)) {
                    cancel(run.id());
                }
                execute(run, headers);
                RunStatus status = store.statusOf(run.id()).orElse(RunStatus.FAILED);
                statuses.add(status);
                if (status != RunStatus.COMPLETED) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            LOG.error("Series {} failed", seriesId, e);
            store.changeSeriesStatus(seriesId, RunStatus.FAILED, headers.redact(String.valueOf(e.getMessage())));
            return;
        }
        finishSeries(seriesId, repeat, statuses);
    }

    private void finishSeries(String seriesId, int repeat, List<RunStatus> statuses) {
        if (registry.isShuttingDown()) {
            store.changeSeriesStatus(seriesId, RunStatus.INTERRUPTED, RunStore.INTERRUPTED_REASON);
        } else if (registry.isCancelRequested(seriesId)) {
            store.changeSeriesStatus(seriesId, RunStatus.CANCELLED, null);
        } else if (statuses.size() == repeat && statuses.stream().allMatch(s -> s == RunStatus.COMPLETED)) {
            store.changeSeriesStatus(seriesId, RunStatus.COMPLETED, null);
        } else {
            String last = statuses.isEmpty() ? "none" : statuses.getLast().wire();
            store.changeSeriesStatus(seriesId, RunStatus.FAILED,
                    "repeat " + statuses.size() + " of " + repeat + " ended with status '" + last + "'");
        }
    }

    /**
     * On shutdown: stop every engine, then give the run threads a little time to store
     * their partial results (marked "interrupted") before the database closes.
     */
    @PreDestroy
    public void shutdown() {
        registry.stopAll();
        threads.shutdown();
        try {
            if (!threads.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                threads.shutdownNow();
            }
        } catch (InterruptedException e) {
            threads.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
