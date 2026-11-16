package com.elevate.analysis.outcome;

/**
 * How the requests of a run ended, measured against their expectations. A request is
 * "handled correctly" when it is not a failure; the rest is split by kind.
 *
 * @param totalRequests            all requests
 * @param handledCorrectly         answered as expected
 * @param handledCorrectlyPct      the same in percent
 * @param acceptedInvalidInput     2xx for invalid input
 * @param rejectedValidInput       4xx for valid input
 * @param serverError              5xx
 * @param connection               no response
 * @param rateLimited              429
 * @param other                    anything else
 * @param unexpectedOutcomeCount   accepted invalid + rejected valid
 * @param unexpectedOutcomeRatePct the same in percent
 * @param serverErrorRatePct       (5xx + no response) in percent
 */
public record OutcomeSummary(long totalRequests, long handledCorrectly, Double handledCorrectlyPct,
                             long acceptedInvalidInput, long rejectedValidInput, long serverError, long connection,
                             long rateLimited, long other, long unexpectedOutcomeCount,
                             Double unexpectedOutcomeRatePct, Double serverErrorRatePct) {
}
