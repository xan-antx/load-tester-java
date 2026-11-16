package com.elevate.analysis.compare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.elevate.analysis.stats.CaseStats;
import com.elevate.analysis.stats.Numbers;
import com.elevate.analysis.stats.StatsTable;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A run compared with the saved baseline of its target: every case whose failure rate rose
 * by at least {@link CaseComparison#CHANGE_THRESHOLD} points (both runs having enough
 * requests), and the change of the overall p95.
 *
 * @param regressions  cases that got worse, biggest increase first
 * @param baselineP95  overall p95 of the baseline (ms)
 * @param currentP95   overall p95 of this run (ms)
 * @param p95ChangePct percent change of p95, or {@code null} when unknown
 * @param threshold    percentage points that count as a regression
 * @param minRequests  requests both runs need for a case to be compared
 */
public record RegressionReport(List<Regression> regressions, Double baselineP95, Double currentP95,
                               Double p95ChangePct, double threshold, int minRequests) {

    /**
     * @param baseline the baseline run's stats (may be {@code null})
     * @param current  this run's stats (may be {@code null})
     * @return the comparison
     */
    public static RegressionReport detect(StatsTable baseline, StatsTable current) {
        Map<String, CaseStats> before = CaseComparison.byName(baseline);
        Map<String, CaseStats> after = CaseComparison.byName(current);
        List<Regression> regressions = new ArrayList<>();
        for (Map.Entry<String, CaseStats> entry : before.entrySet()) {
            CaseStats now = after.get(entry.getKey());
            CaseStats then = entry.getValue();
            if (now == null || then.requests() < CaseComparison.MIN_REQUESTS
                    || now.requests() < CaseComparison.MIN_REQUESTS) {
                continue;
            }
            double baseRate = then.failureRatePct();
            double curRate = now.failureRatePct();
            if (curRate - baseRate >= CaseComparison.CHANGE_THRESHOLD) {
                regressions.add(new Regression(entry.getKey(), Numbers.round(baseRate, 1), Numbers.round(curRate, 1),
                        Numbers.round(curRate - baseRate, 1), then.requests(), now.requests()));
            }
        }
        regressions.sort(Comparator.comparingDouble(Regression::increase).reversed()
                .thenComparing(Regression::caseName));
        Double baseP95 = baseline == null ? null : baseline.aggregated().p95Ms();
        Double curP95 = current == null ? null : current.aggregated().p95Ms();
        Double change = baseP95 != null && baseP95 != 0 && curP95 != null
                ? Numbers.round((curP95 - baseP95) / baseP95 * 100, 1) : null;
        return new RegressionReport(regressions, baseP95, curP95, change, CaseComparison.CHANGE_THRESHOLD,
                CaseComparison.MIN_REQUESTS);
    }

    /**
     * @param caseName         case label (JSON {@code case})
     * @param baselineRate     failure rate in the baseline (%)
     * @param currentRate      failure rate now (%)
     * @param increase         percentage points
     * @param baselineRequests requests in the baseline
     * @param currentRequests  requests now
     */
    public record Regression(@JsonProperty("case") String caseName, double baselineRate, double currentRate,
                             double increase, long baselineRequests, long currentRequests) {
    }
}
