package com.elevate.analysis.capacity;

import java.util.List;

/**
 * One step of a capacity run as the API reports it, with its verdict.
 *
 * @param step                     step number, from 0
 * @param plannedUsers             users the plan asked for
 * @param users                    highest number of active users seen
 * @param seconds                  seconds of the step that ran
 * @param complete                 at least 75% of the step ran (a partial last step is not judged)
 * @param requests                 requests in the step
 * @param rps                      requests per second
 * @param p95Ms                    p95 of the step
 * @param avgMs                    average of the step
 * @param failureRatePct           failed requests in percent
 * @param serverErrorRatePct       5xx + no answer, in percent
 * @param unexpectedOutcomeRatePct accepted invalid + rejected valid, in percent
 * @param rateLimitedPct           429 answers, in percent
 * @param verdict                  {@code PASS}, {@code FAIL} or {@code null}
 * @param failedChecks             the thresholds it missed, in words
 */
public record CapacityStep(int step, int plannedUsers, int users, int seconds, boolean complete, long requests,
                           double rps, Double p95Ms, Double avgMs, Double failureRatePct,
                           Double serverErrorRatePct, Double unexpectedOutcomeRatePct, Double rateLimitedPct,
                           String verdict, List<String> failedChecks) {
}
