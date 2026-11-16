package com.elevate.service;

import java.util.concurrent.Semaphore;

import org.springframework.stereotype.Component;

import com.elevate.config.AppProperties;
import com.elevate.exception.TooManyActiveRunsException;

/**
 * At most {@code MAX_ACTIVE_RUNS} load tests in progress at once, counted the way a user
 * sees them: a split group, a capacity run and a repeat series each count as one.
 *
 * <p>A {@link Semaphore} with one permit per allowed run. {@link Semaphore#tryAcquire()} is
 * atomic, so two start requests arriving together can never both take the last slot (the
 * Python version needed a lock around "count, then create" for the same guarantee).
 */
@Component
public class ActiveRunLimiter {

    private final int maxActiveRuns;
    private final Semaphore permits;

    /** @param properties reads {@code MAX_ACTIVE_RUNS} */
    public ActiveRunLimiter(AppProperties properties) {
        this.maxActiveRuns = properties.maxActiveRuns();
        this.permits = new Semaphore(maxActiveRuns);
    }

    /**
     * Takes a slot for a new run.
     *
     * @throws TooManyActiveRunsException (429) when every slot is taken
     */
    public void acquire() {
        if (!permits.tryAcquire()) {
            throw new TooManyActiveRunsException(activeRuns(), maxActiveRuns);
        }
    }

    /** Gives a slot back (exactly once per {@link #acquire()}). */
    public void release() {
        permits.release();
    }

    /** Runs in progress now. */
    public int activeRuns() {
        return maxActiveRuns - permits.availablePermits();
    }
}
