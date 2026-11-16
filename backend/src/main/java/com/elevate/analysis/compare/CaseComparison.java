package com.elevate.analysis.compare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.elevate.analysis.stats.CaseStats;
import com.elevate.analysis.stats.Numbers;
import com.elevate.analysis.stats.StatsTable;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Lines up every case across 2–4 runs, so cases whose failure rate moved stand out even
 * when the overall rate barely changes.
 *
 * @param minRequests cells with fewer requests are "low sample" and not trusted
 * @param threshold   percentage points of spread that count as "changed"
 * @param rows        one row per case: changed rows first (biggest spread first), then by name
 */
public record CaseComparison(int minRequests, double threshold, List<Row> rows) {

    /** Fewer requests than this is a low sample. */
    public static final int MIN_REQUESTS = 5;
    /** Percentage points between runs that count as changed. */
    public static final double CHANGE_THRESHOLD = 10.0;

    /**
     * @param tables one stats table per run (a {@code null} table means the run has no results)
     * @return the comparison
     */
    public static CaseComparison of(List<StatsTable> tables) {
        List<Map<String, CaseStats>> runs = tables.stream().map(CaseComparison::byName).toList();
        TreeSet<String> names = new TreeSet<>();
        runs.forEach(r -> names.addAll(r.keySet()));

        List<Row> rows = new ArrayList<>();
        for (String name : names) {
            List<Cell> cells = new ArrayList<>();
            List<Double> trusted = new ArrayList<>();
            for (Map<String, CaseStats> run : runs) {
                CaseStats s = run.get(name);
                if (s == null) {
                    cells.add(null);
                    continue;
                }
                Double rate = s.failureRatePct();
                boolean lowSample = s.requests() < MIN_REQUESTS;
                if (rate != null && !lowSample) {
                    trusted.add(rate);
                }
                cells.add(new Cell(s.requests(), s.failures(), Numbers.round(rate, 1), lowSample));
            }
            double spread = trusted.size() >= 2
                    ? trusted.stream().mapToDouble(Double::doubleValue).max().orElseThrow()
                    - trusted.stream().mapToDouble(Double::doubleValue).min().orElseThrow()
                    : 0.0;
            rows.add(new Row(name, cells, Numbers.round(spread, 1), spread >= CHANGE_THRESHOLD));
        }
        rows.sort(Comparator.comparing((Row r) -> !r.changed())
                .thenComparing(r -> r.changed() ? -r.spread() : 0.0)
                .thenComparing(Row::caseName));
        return new CaseComparison(MIN_REQUESTS, CHANGE_THRESHOLD, rows);
    }

    /** Per-case rows of a table by name (empty for a run without results). */
    static Map<String, CaseStats> byName(StatsTable table) {
        if (table == null) {
            return Map.of();
        }
        Map<String, CaseStats> map = new LinkedHashMap<>();
        table.cases().forEach(c -> map.put(c.name(), c));
        return map;
    }

    /**
     * @param caseName case label (JSON {@code case})
     * @param cells    one per run, {@code null} where the run did not have the case
     * @param spread   biggest difference in failure rate between trusted cells
     * @param changed  {@code spread >= threshold}
     */
    public record Row(@JsonProperty("case") String caseName, List<Cell> cells, double spread, boolean changed) {
    }

    /**
     * @param requests  requests of the case in that run
     * @param failures  failures of the case in that run
     * @param rate      failure rate in percent (1 decimal), {@code null} without requests
     * @param lowSample fewer than {@link #MIN_REQUESTS} requests
     */
    public record Cell(long requests, long failures, Double rate, boolean lowSample) {
    }
}
