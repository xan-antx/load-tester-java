package com.elevate.analysis.slo;

import java.util.List;

/**
 * The PASS / FAIL verdict of a run against its SLO.
 *
 * @param verdict     {@code PASS}, {@code FAIL}, or {@code null} when nothing could be measured
 * @param checks      one per threshold
 * @param passed      thresholds met
 * @param failed      thresholds missed
 * @param notMeasured thresholds whose metric is unknown for this run
 */
public record SloVerdict(String verdict, List<Check> checks, int passed, int failed, int notMeasured) {

    /** Verdict text when every measured threshold holds. */
    public static final String PASS = "PASS";
    /** Verdict text when any threshold is missed. */
    public static final String FAIL = "FAIL";

    /**
     * One threshold compared with its measurement.
     *
     * @param key        JSON key, e.g. {@code p95_ms}
     * @param label      readable name
     * @param unit       unit
     * @param comparison {@code <=} or {@code >=}
     * @param target     the threshold
     * @param actual     the measured value (2 decimals), or {@code null}
     * @param passed     whether it holds, or {@code null} when not measured
     */
    public record Check(String key, String label, String unit, String comparison, double target, Double actual,
                        Boolean passed) {
    }
}
