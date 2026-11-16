package com.elevate.analysis.slo;

/**
 * The measured values SLO thresholds are compared with; {@code null} means "not measured".
 *
 * @param p95Ms                    p95 response time
 * @param avgMs                    average response time
 * @param rps                      requests per second
 * @param serverErrorRatePct       (5xx + connection failures) / requests, in percent
 * @param unexpectedOutcomeRatePct (accepted invalid + rejected valid) / requests, in percent
 */
public record RunMetrics(Double p95Ms, Double avgMs, Double rps, Double serverErrorRatePct,
                         Double unexpectedOutcomeRatePct) {
}
