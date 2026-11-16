package com.elevate.analysis.stats;

import org.HdrHistogram.Histogram;

/**
 * Everything measured for one case (an edge-case label or a page path).
 *
 * <p>The histogram is a mutable object; a measurement owns its histogram and never
 * hands out a shared one: {@link #plus(CaseMeasurement)} builds a new one.
 *
 * @param name         case label or page path
 * @param method       HTTP method, e.g. {@code POST}
 * @param requests     requests sent
 * @param failures     requests whose outcome was a failure
 * @param sumMicros    sum of all response times (for an exact average)
 * @param minMicros    fastest response ({@code Long.MAX_VALUE} when none)
 * @param maxMicros    slowest response (0 when none)
 * @param contentBytes sum of response body sizes
 * @param histogram    response times in microseconds
 */
public record CaseMeasurement(String name, String method, long requests, long failures, long sumMicros,
                              long minMicros, long maxMicros, long contentBytes, Histogram histogram) {

    /** A measurement with nothing recorded yet. */
    public static CaseMeasurement empty(String name, String method) {
        return new CaseMeasurement(name, method, 0, 0, 0, Long.MAX_VALUE, 0, 0, Histograms.newHistogram());
    }

    /**
     * The exact combination of two measurements of the same case: counts and sums add up,
     * min/max take the extremes, and the histograms are added value by value.
     *
     * @param other another measurement (from a parallel engine instance, or another case)
     * @return a new measurement; neither input is changed
     */
    public CaseMeasurement plus(CaseMeasurement other) {
        Histogram merged = Histograms.copyOf(histogram);
        merged.add(other.histogram);
        return new CaseMeasurement(name, method,
                requests + other.requests, failures + other.failures, sumMicros + other.sumMicros,
                Math.min(minMicros, other.minMicros), Math.max(maxMicros, other.maxMicros),
                contentBytes + other.contentBytes, merged);
    }
}
