package com.elevate.analysis.stats;

import org.HdrHistogram.Histogram;

/**
 * What happened during one second of a run (interval values, not running totals).
 *
 * @param epochSecond Unix time of the end of the second
 * @param users       virtual users active at the end of the second
 * @param requests    requests completed during the second
 * @param failures    failed requests during the second
 * @param sumMicros   sum of their response times
 * @param histogram   their response times (microseconds)
 */
public record TimelineSample(long epochSecond, int users, long requests, long failures, long sumMicros,
                             Histogram histogram) {

    /**
     * Combines two samples of the same second from parallel engine instances: users and
     * counts add up, histograms are added exactly.
     */
    public TimelineSample plus(TimelineSample other) {
        Histogram merged = Histograms.copyOf(histogram);
        merged.add(other.histogram);
        return new TimelineSample(epochSecond, users + other.users, requests + other.requests,
                failures + other.failures, sumMicros + other.sumMicros, merged);
    }
}
