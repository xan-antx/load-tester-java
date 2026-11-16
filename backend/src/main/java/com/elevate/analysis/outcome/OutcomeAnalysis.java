package com.elevate.analysis.outcome;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.elevate.analysis.stats.FailureCount;
import com.elevate.analysis.stats.Numbers;

/**
 * Rates derived from a run's failures: the outcome summary, the server-error rate and the
 * "problem rate".
 *
 * <p>The plain failure rate counts every non-2xx as a failure, including the 400s a correct
 * API <i>should</i> send for invalid edge cases. The problem rate counts only what means
 * something went wrong: 5xx, 429 and connection failures.
 */
public final class OutcomeAnalysis {

    private OutcomeAnalysis() {
    }

    /** Occurrences per outcome. */
    public static Map<Outcome, Long> countByOutcome(List<FailureCount> failures) {
        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        failures.forEach(f -> counts.merge(f.outcome(), f.occurrences(), Long::sum));
        return counts;
    }

    /**
     * (5xx + connection failures) / requests, in percent.
     *
     * @param failures      failure tallies
     * @param totalRequests request count, or {@code null} when unknown
     * @return the rate, or {@code null} when unknown
     */
    public static Double serverErrorRatePct(List<FailureCount> failures, Long totalRequests) {
        long server = failures.stream().filter(f -> f.outcome().isServerFailure())
                .mapToLong(FailureCount::occurrences).sum();
        return Numbers.percent(server, totalRequests, 2);
    }

    /**
     * Problem count and rate (5xx + 429 + connection failures), the rate with one decimal.
     *
     * @param failures      failure tallies
     * @param totalRequests request count, or {@code null} when unknown
     * @return the problem rate
     */
    public static ProblemRate problemRate(List<FailureCount> failures, Long totalRequests) {
        long problems = failures.stream().filter(f -> f.outcome().isProblem())
                .mapToLong(FailureCount::occurrences).sum();
        return new ProblemRate(problems, Numbers.percent(problems, totalRequests, 1));
    }

    /**
     * The outcome summary of a run whose cases carry expectations.
     *
     * @param failures        failure tallies
     * @param totalRequests   request count, or {@code null} when unknown
     * @param hasExpectations whether the run's cases carry expectations
     * @return the summary, or {@code null} without expectations or a known total
     */
    public static OutcomeSummary summarize(List<FailureCount> failures, Long totalRequests, boolean hasExpectations) {
        if (!hasExpectations || totalRequests == null) {
            return null;
        }
        Map<Outcome, Long> counts = countByOutcome(failures);
        long failed = counts.values().stream().mapToLong(Long::longValue).sum();
        long unexpected = count(counts, Outcome.ACCEPTED_INVALID_INPUT) + count(counts, Outcome.REJECTED_VALID_INPUT);
        long server = count(counts, Outcome.SERVER_ERROR) + count(counts, Outcome.CONNECTION_ERROR);
        long handled = Math.max(totalRequests - failed, 0);
        return new OutcomeSummary(totalRequests, handled, Numbers.percent(handled, totalRequests, 2),
                count(counts, Outcome.ACCEPTED_INVALID_INPUT), count(counts, Outcome.REJECTED_VALID_INPUT),
                count(counts, Outcome.SERVER_ERROR), count(counts, Outcome.CONNECTION_ERROR),
                count(counts, Outcome.RATE_LIMITED),
                count(counts, Outcome.UNEXPECTED_STATUS) + count(counts, Outcome.REJECTED),
                unexpected, Numbers.percent(unexpected, totalRequests, 2), Numbers.percent(server, totalRequests, 2));
    }

    private static long count(Map<Outcome, Long> counts, Outcome outcome) {
        return counts.getOrDefault(outcome, 0L);
    }

    /**
     * @param problemCount 5xx + 429 + connection failures
     * @param problemRate  the same in percent (one decimal), or {@code null} when unknown
     */
    public record ProblemRate(long problemCount, Double problemRate) {
    }
}
