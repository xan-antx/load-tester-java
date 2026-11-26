package com.elevate.service;

import java.time.Duration;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.exception.ConflictException;
import com.elevate.exception.NotFoundException;
import com.elevate.model.RunStatus;
import com.elevate.queue.JobMessage;
import com.elevate.queue.JobQueue;

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
 *
 * <p>Queue mode ({@code USE_SQS=true}, not deployed — as in the Python version): each job is
 * sent to the {@link JobQueue} instead of running here, and a worker process runs it. The
 * top-level task then waits until the run has finished, so it still counts against
 * {@code MAX_ACTIVE_RUNS} and a series still runs its repeats one after another.
 */
@Service
public class RunExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RunExecutor.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 20;
    private static final Duration QUEUE_POLL = Duration.ofSeconds(1);

    private final RunStore store;
    private final RunRegistry registry;
    private final ActiveRunLimiter limiter;
    private final JobRunner jobs;
    private final JobQueue queue;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param store    database writes
     * @param registry running engines and cancel requests
     * @param limiter  the MAX_ACTIVE_RUNS slots
     * @param jobs     runs a job on a load engine
     * @param queue      the job queue in queue mode (absent otherwise)
     */
    public RunExecutor(RunStore store, RunRegistry registry, ActiveRunLimiter limiter, JobRunner jobs,
                       ObjectProvider<JobQueue> queue) {
        this.store = store;
        this.registry = registry;
        this.limiter = limiter;
        this.jobs = jobs;
        this.queue = queue.getIfAvailable();
    }

    /** Whether jobs go to a queue instead of running in this process. */
    public boolean isQueueMode() {
        return queue != null;
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
     * @throws ConflictException in queue mode, where the engine runs in another process
     */
    public void cancel(String id) {
        if (isQueueMode()) {
            throw new ConflictException("Stopping a test is not available in queue (SQS) mode: the worker runs on "
                    + "another machine, so this server cannot reach its load engine.");
        }
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
        executeHere(run, headers);
        if (isQueueMode()) {
            awaitEnd(run.id());
        }
    }

    private void executeHere(PreparedRun run, RequestHeaders headers) {
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
        JobPlan plan;
        try {
            plan = store.loadPlan(jobId);
            if (isQueueMode()) {
                queue.send(JobMessage.of(plan));
                return;
            }
        } catch (RuntimeException e) {
            jobs.fail(jobId, e, headers);
            return;
        }
        jobs.run(plan, headers);
    }

    /** Queue mode: waits (polling the database the worker writes to) until the run has finished. */
    private void awaitEnd(String runId) {
        try {
            while (!store.statusOf(runId).map(RunStatus::isTerminal).orElse(true) && !registry.isShuttingDown()) {
                Thread.sleep(QUEUE_POLL.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
