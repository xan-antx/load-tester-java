package com.elevate.dto;

import com.elevate.analysis.compare.RegressionReport;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * Body of {@code /api/regressions/{id}}.
 *
 * @param targetUrl         the run's target
 * @param baselineId        the baseline compared with, or {@code null}
 * @param isCurrentBaseline the run is itself the baseline
 * @param baseline          a summary of the baseline run, or {@code null}
 * @param report            the comparison, unwrapped into this object; {@code null} without a baseline
 * @param p95Exact          always {@code true}: a split baseline's p95 comes from exactly merged histograms
 */
public record RegressionsResponse(String targetUrl, String baselineId, boolean isCurrentBaseline, Baseline baseline,
                                  @JsonUnwrapped RegressionReport report, boolean p95Exact) {

    /**
     * @param id        baseline run id
     * @param users     its users
     * @param createdAt its creation time (UTC)
     * @param isGroup   it was a split run
     */
    public record Baseline(String id, int users, String createdAt, boolean isGroup) {
    }
}
