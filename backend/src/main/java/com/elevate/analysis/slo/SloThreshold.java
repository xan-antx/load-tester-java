package com.elevate.analysis.slo;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;

/**
 * The thresholds an SLO may set, each with its label, unit, direction, allowed range and
 * the measured metric it is compared with.
 */
public enum SloThreshold {
    P95_MS("p95_ms", "p95 response time", "ms", true, 1, 600_000, RunMetrics::p95Ms),
    AVG_MS("avg_ms", "Average response time", "ms", true, 1, 600_000, RunMetrics::avgMs),
    MAX_SERVER_ERROR_RATE_PCT("max_server_error_rate_pct", "Server errors (5xx + connection)", "%", true, 0, 100,
            RunMetrics::serverErrorRatePct),
    MAX_UNEXPECTED_OUTCOME_RATE_PCT("max_unexpected_outcome_rate_pct", "Unexpected answers", "%", true, 0, 100,
            RunMetrics::unexpectedOutcomeRatePct),
    MIN_RPS("min_rps", "Throughput", "req/s", false, 0, 100_000, RunMetrics::rps);

    private final String key;
    private final String label;
    private final String unit;
    private final boolean upperBound;
    private final double low;
    private final double high;
    private final Function<RunMetrics, Double> metric;

    SloThreshold(String key, String label, String unit, boolean upperBound, double low, double high,
                 Function<RunMetrics, Double> metric) {
        this.key = key;
        this.label = label;
        this.unit = unit;
        this.upperBound = upperBound;
        this.low = low;
        this.high = high;
        this.metric = metric;
    }

    /** The JSON key, e.g. {@code p95_ms}. */
    public String key() {
        return key;
    }

    /** Readable name, e.g. {@code p95 response time}. */
    public String label() {
        return label;
    }

    /** Unit shown next to values. */
    public String unit() {
        return unit;
    }

    /** {@code <=} for maxima, {@code >=} for minima. */
    public String comparison() {
        return upperBound ? "<=" : ">=";
    }

    /** Lowest allowed setting. */
    public double low() {
        return low;
    }

    /** Highest allowed setting. */
    public double high() {
        return high;
    }

    /**
     * Whether a capacity run judges each step against this threshold. Throughput is left
     * out: it is low at the small steps by design.
     */
    public boolean judgedPerStep() {
        return this != MIN_RPS;
    }

    /** The measured value this threshold is compared with, or {@code null} if unknown. */
    public Double measured(RunMetrics metrics) {
        return metric.apply(metrics);
    }

    /** Whether {@code actual} meets {@code target}. */
    public boolean isMet(double actual, double target) {
        return upperBound ? actual <= target : actual >= target;
    }

    /** Looks a threshold up by its JSON key. */
    public static Optional<SloThreshold> fromKey(String key) {
        return Arrays.stream(values()).filter(t -> t.key.equals(key)).findFirst();
    }
}
