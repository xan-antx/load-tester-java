package com.elevate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.engine.EngineResult;
import com.elevate.engine.LoadEngine;
import com.elevate.engine.StopReason;
import com.elevate.model.RunStatus;

/**
 * Runs one stored job on a load engine and stores the outcome. Used by the backend itself
 * (local mode) and by the queue worker, which builds the plan from the queue message.
 */
@Component
public class JobRunner {

    private static final Logger LOG = LoggerFactory.getLogger(JobRunner.class);

    private final RunStore store;
    private final RunRegistry registry;
    private final EngineSpecFactory specs;

    /**
     * @param store    database writes
     * @param registry running engines and cancel requests
     * @param specs    builds engine specifications
     */
    public JobRunner(RunStore store, RunRegistry registry, EngineSpecFactory specs) {
        this.store = store;
        this.registry = registry;
        this.specs = specs;
    }

    /**
     * Runs the job and blocks until it ends: status {@code running}, then {@code completed},
     * {@code cancelled}, {@code interrupted} (backend shutting down) or {@code failed}.
     *
     * @param plan    the job
     * @param headers extra request headers (values in memory only)
     */
    public void run(JobPlan plan, RequestHeaders headers) {
        String jobId = plan.jobId();
        try {
            LoadEngine engine = new LoadEngine(specs.create(plan, headers));
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
            fail(jobId, e, headers);
        } finally {
            registry.unregister(jobId);
        }
    }

    /** Marks a job failed with the (redacted) reason. */
    void fail(String jobId, RuntimeException e, RequestHeaders headers) {
        LOG.error("Load test {} failed", jobId, e);
        store.changeJobStatus(jobId, RunStatus.FAILED, headers.redact(String.valueOf(e.getMessage())));
    }
}
