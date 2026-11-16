package com.elevate.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

import com.elevate.engine.LoadEngine;

/**
 * In-memory state of the runs this process is executing: the engine of each running job,
 * the ids whose cancellation was requested, and whether the backend is shutting down.
 *
 * <p>Cancel and start can race. {@link #register} first publishes the engine, then checks
 * for a cancel request; {@link #requestCancel} first records the request, then looks for an
 * engine. Whatever the interleaving, at least one of them sees the other, so a cancel is
 * never lost.
 */
@Component
public class RunRegistry {

    private final ConcurrentMap<String, LoadEngine> engines = new ConcurrentHashMap<>();
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();
    private volatile boolean shuttingDown;

    /** Publishes the engine of a starting job (stopping it at once if a cancel came first). */
    void register(String jobId, LoadEngine engine) {
        engines.put(jobId, engine);
        if (cancelRequested.contains(jobId)) {
            engine.stop();
        }
    }

    /** Forgets the engine of a finished job. */
    void unregister(String jobId) {
        engines.remove(jobId);
    }

    /** Records a cancel request and stops the run's engine if it is running. */
    void requestCancel(String runId) {
        cancelRequested.add(runId);
        LoadEngine engine = engines.get(runId);
        if (engine != null) {
            engine.stop();
        }
    }

    /** Whether a cancel was requested for this run. */
    boolean isCancelRequested(String runId) {
        return cancelRequested.contains(runId);
    }

    /** The backend is stopping: stop every engine; their runs end as "interrupted". */
    void stopAll() {
        shuttingDown = true;
        engines.values().forEach(LoadEngine::stop);
    }

    /** Whether the backend is shutting down. */
    boolean isShuttingDown() {
        return shuttingDown;
    }
}
