package com.elevate.analysis.stats;

import java.util.List;

/**
 * The statistics of a run: one row per case and the {@code Aggregated} total, which is
 * computed from the merged histogram of every case (not from the rows).
 *
 * @param cases      per-case rows, in configuration order
 * @param aggregated the total row
 */
public record StatsTable(List<CaseStats> cases, CaseStats aggregated) {

    /** Name of the total row, as Locust calls it. */
    public static final String AGGREGATED = "Aggregated";

    /**
     * Builds the table of a run's results.
     *
     * @param results the results
     * @return the table, or {@code null} when nothing was recorded
     */
    public static StatsTable of(RunResults results) {
        if (results.isEmpty()) {
            return null;
        }
        double elapsed = results.elapsedSeconds();
        List<CaseStats> rows = results.cases().stream().map(c -> CaseStats.of(c, elapsed)).toList();
        CaseMeasurement total = results.cases().stream()
                .reduce(CaseMeasurement.empty(AGGREGATED, ""), CaseMeasurement::plus);
        return new StatsTable(rows, CaseStats.of(total, elapsed));
    }

    /** Total request count. */
    public long totalRequests() {
        return aggregated.requests();
    }
}
