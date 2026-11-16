package com.elevate.model;

import java.util.EnumMap;
import java.util.Map;

import com.elevate.analysis.slo.Slo;
import com.elevate.analysis.slo.SloThreshold;

import jakarta.persistence.Embeddable;

/**
 * The SLO of a run as stored: one nullable column per threshold (an embeddable record).
 *
 * @param p95Ms                       p95 limit in ms
 * @param avgMs                       average limit in ms
 * @param maxServerErrorRatePct       server-error rate limit in %
 * @param maxUnexpectedOutcomeRatePct unexpected-answer rate limit in %
 * @param minRps                      minimum throughput
 */
@Embeddable
public record SloSettings(Double p95Ms, Double avgMs, Double maxServerErrorRatePct,
                          Double maxUnexpectedOutcomeRatePct, Double minRps) {

    /**
     * @param slo thresholds, or {@code null}
     * @return the stored form, or {@code null} without an SLO
     */
    public static SloSettings from(Slo slo) {
        if (slo == null) {
            return null;
        }
        return new SloSettings(slo.get(SloThreshold.P95_MS), slo.get(SloThreshold.AVG_MS),
                slo.get(SloThreshold.MAX_SERVER_ERROR_RATE_PCT), slo.get(SloThreshold.MAX_UNEXPECTED_OUTCOME_RATE_PCT),
                slo.get(SloThreshold.MIN_RPS));
    }

    /** The thresholds, or {@code null} when none is set. */
    public Slo toSlo() {
        Map<SloThreshold, Double> thresholds = new EnumMap<>(SloThreshold.class);
        put(thresholds, SloThreshold.P95_MS, p95Ms);
        put(thresholds, SloThreshold.AVG_MS, avgMs);
        put(thresholds, SloThreshold.MAX_SERVER_ERROR_RATE_PCT, maxServerErrorRatePct);
        put(thresholds, SloThreshold.MAX_UNEXPECTED_OUTCOME_RATE_PCT, maxUnexpectedOutcomeRatePct);
        put(thresholds, SloThreshold.MIN_RPS, minRps);
        return thresholds.isEmpty() ? null : new Slo(thresholds);
    }

    private static void put(Map<SloThreshold, Double> map, SloThreshold key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
