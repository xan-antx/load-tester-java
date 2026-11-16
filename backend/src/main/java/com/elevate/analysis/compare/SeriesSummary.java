package com.elevate.analysis.compare;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.elevate.analysis.stats.Numbers;
import com.elevate.analysis.stats.StatsTable;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Statistics across the repeats of a series: one load test is one sample, so repeating it
 * and looking at the spread shows whether a difference between two results is real.
 *
 * @param repeats repeats summarised
 * @param overall throughput, p95 and problem rate across the repeats
 * @param cases   per case failure rate across the repeats, highest mean first
 */
public record SeriesSummary(int repeats, Overall overall, List<CaseSpread> cases) {

    /**
     * @param runs one entry per completed repeat, in order
     * @return the summary
     */
    public static SeriesSummary of(List<Repeat> runs) {
        List<Double> throughput = new ArrayList<>();
        List<Double> p95 = new ArrayList<>();
        List<Double> problem = new ArrayList<>();
        Map<String, List<Double>> caseRates = new LinkedHashMap<>();
        for (Repeat run : runs) {
            StatsTable t = run.stats();
            throughput.add(t == null ? null : t.aggregated().requestsPerSecond());
            p95.add(t == null ? null : t.aggregated().p95Ms());
            problem.add(run.problemRate());
            if (t != null) {
                t.cases().stream().filter(c -> c.requests() > 0)
                        .forEach(c -> caseRates.computeIfAbsent(c.name(), k -> new ArrayList<>()).add(c.failureRatePct()));
            }
        }
        List<CaseSpread> cases = caseRates.entrySet().stream()
                .map(e -> CaseSpread.of(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingDouble(CaseSpread::meanRate).reversed().thenComparing(CaseSpread::caseName))
                .toList();
        return new SeriesSummary(runs.size(),
                new Overall(Spread.of(throughput), Spread.of(p95), Spread.of(problem)), cases);
    }

    /**
     * @param stats       the repeat's statistics
     * @param problemRate its problem rate (%), or {@code null}
     */
    public record Repeat(StatsTable stats, Double problemRate) {
    }

    /**
     * @param throughput  requests per second
     * @param p95         p95 in ms
     * @param problemRate problem rate in %
     */
    public record Overall(Spread throughput, Spread p95, Spread problemRate) {
    }

    /**
     * Mean, sample standard deviation (n − 1), min and max of the known values.
     *
     * @param mean   mean, or {@code null} without values
     * @param std    standard deviation, {@code null} with fewer than 2 values
     * @param min    smallest
     * @param max    largest
     * @param values every value in order ({@code null} where unknown)
     */
    public record Spread(Double mean, Double std, Double min, Double max, List<Double> values) {

        static Spread of(List<Double> values) {
            List<Double> known = values.stream().filter(Objects::nonNull).toList();
            List<Double> all = Collections.unmodifiableList(new ArrayList<>(values));
            if (known.isEmpty()) {
                return new Spread(null, null, null, null, all);
            }
            double mean = known.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            Double std = null;
            if (known.size() >= 2) {
                double squares = known.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum();
                std = Numbers.round(Math.sqrt(squares / (known.size() - 1)), 2);
            }
            return new Spread(Numbers.round(mean, 2), std,
                    Numbers.round(Collections.min(known), 2), Numbers.round(Collections.max(known), 2), all);
        }
    }

    /**
     * @param caseName case label (JSON {@code case})
     * @param meanRate mean failure rate (%)
     * @param minRate  lowest
     * @param maxRate  highest
     * @param runs     repeats in which the case had requests
     */
    public record CaseSpread(@JsonProperty("case") String caseName, double meanRate, double minRate, double maxRate,
                             int runs) {

        static CaseSpread of(String name, List<Double> rates) {
            double mean = rates.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            return new CaseSpread(name, Numbers.round(mean, 1), Numbers.round(Collections.min(rates), 1),
                    Numbers.round(Collections.max(rates), 1), rates.size());
        }
    }
}
