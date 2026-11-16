package com.elevate.analysis.capacity;

import com.elevate.analysis.slo.RunMetrics;
import com.elevate.analysis.stats.Numbers;

/**
 * What happened during one step of a capacity run, recorded by the engine at the step's end.
 * The p95 comes from a histogram of that step's requests only, so it is exact for the step.
 *
 * @param index           step number, from 0
 * @param plannedUsers    users the plan asked for
 * @param users           highest number of active users seen during the step
 * @param seconds         seconds of the step that ran (fewer when stopped mid-step)
 * @param requests        requests completed in the step
 * @param failures        failed requests in the step
 * @param sumMicros       sum of their response times
 * @param p95Ms           p95 of the step, or {@code null} without requests
 * @param serverErrors    5xx answers
 * @param connectionErrors requests without an answer
 * @param acceptedInvalid 2xx for invalid input
 * @param rejectedValid   4xx for valid input
 * @param rateLimited     429 answers
 */
public record StepRecord(int index, int plannedUsers, int users, int seconds, long requests, long failures,
                         long sumMicros, Double p95Ms, long serverErrors, long connectionErrors,
                         long acceptedInvalid, long rejectedValid, long rateLimited) {

    /** Average response time in ms (2 decimals), or {@code null} without requests. */
    public Double avgMs() {
        return requests == 0 ? null : Numbers.round(sumMicros / 1000.0 / requests, 2);
    }

    /** The measurements the per-step SLO is judged on. */
    public RunMetrics metrics() {
        return new RunMetrics(p95Ms, avgMs(), requests / (double) Math.max(seconds, 1),
                Numbers.percent(serverErrors + connectionErrors, requests, 2),
                Numbers.percent(acceptedInvalid + rejectedValid, requests, 2));
    }
}
