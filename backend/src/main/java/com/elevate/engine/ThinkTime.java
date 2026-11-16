package com.elevate.engine;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * The pause a virtual user takes between two requests: uniformly random between
 * {@code min} and {@code max}, like Locust's {@code wait_time = between(0.5, 2)}.
 *
 * @param min shortest pause
 * @param max longest pause
 */
public record ThinkTime(Duration min, Duration max) {

    /** The Python locustfile's think time: 0.5 to 2 seconds. */
    public static final ThinkTime LOCUST_DEFAULT = new ThinkTime(Duration.ofMillis(500), Duration.ofSeconds(2));

    /** Validates the range. */
    public ThinkTime {
        if (min.isNegative() || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("think time must be 0 <= min <= max");
        }
    }

    /** The next pause. */
    public Duration next(RandomGenerator random) {
        long minMillis = min.toMillis();
        long maxMillis = max.toMillis();
        return Duration.ofMillis(minMillis == maxMillis ? minMillis : random.nextLong(minMillis, maxMillis + 1));
    }
}
