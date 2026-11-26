package com.elevate.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import com.elevate.config.AppProperties;

/**
 * At startup, runs left "queued" or "running" by a previous backend process can never finish
 * (their threads died with it), so they are marked "interrupted". This runs once all beans
 * exist but before the web server accepts requests, so no new run can be caught by mistake.
 * {@code RECOVER_STALE_RUNS=false} turns it off; so does queue mode, where a remote worker
 * may still be running them.
 */
@Component
public class StaleRunRecovery implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(StaleRunRecovery.class);

    private final RunStore store;
    private final AppProperties properties;

    /**
     * @param store      database writes
     * @param properties reads {@code RECOVER_STALE_RUNS}
     */
    public StaleRunRecovery(RunStore store, AppProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // In queue mode a worker may still be running those runs.
        if (!properties.recoverStaleRuns() || properties.queue().useSqs()) {
            return;
        }
        int marked = store.markLeftoversInterrupted();
        if (marked > 0) {
            LOG.warn("Marked {} run(s) from a previous process as interrupted", marked);
        }
    }
}
