package com.elevate.mock;

import java.util.function.LongSupplier;

/**
 * A global "N requests per second" limiter: the count restarts at every new wall-clock
 * second, and every request beyond the limit within one second is refused.
 *
 * <p>Thread safety: {@link #tryAcquire()} is {@code synchronized}. The window second and
 * the count must change together, so a lock is simpler and safer here than two atomics.
 */
public final class FixedWindowRateLimiter {

    private final int limitPerSecond;
    private final LongSupplier clockMillis;
    private long windowSecond = Long.MIN_VALUE;
    private int countInWindow;

    /**
     * @param limitPerSecond requests allowed per second
     * @param clockMillis    the clock, in milliseconds (injectable for tests)
     */
    public FixedWindowRateLimiter(int limitPerSecond, LongSupplier clockMillis) {
        this.limitPerSecond = limitPerSecond;
        this.clockMillis = clockMillis;
    }

    /**
     * Counts one request.
     *
     * @return {@code true} if it is within the limit, {@code false} if it must get a 429
     */
    public synchronized boolean tryAcquire() {
        long second = clockMillis.getAsLong() / 1000;
        if (second != windowSecond) {
            windowSecond = second;
            countInWindow = 0;
        }
        countInWindow++;
        return countInWindow <= limitPerSecond;
    }
}
