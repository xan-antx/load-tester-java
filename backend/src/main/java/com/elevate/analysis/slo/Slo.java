package com.elevate.analysis.slo;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The thresholds a run is judged against (only the ones that were set).
 * Serialized as {@code {"p95_ms": 500, ...}}.
 *
 * @param thresholds target value per threshold
 */
public record Slo(Map<SloThreshold, Double> thresholds) {

    /** An unmodifiable copy in declaration order. */
    public Slo {
        EnumMap<SloThreshold, Double> copy = new EnumMap<>(SloThreshold.class);
        copy.putAll(thresholds);
        thresholds = Collections.unmodifiableMap(copy);
    }

    /** Whether no threshold is set. */
    public boolean isEmpty() {
        return thresholds.isEmpty();
    }

    /** The target of one threshold, or {@code null} if it is not set. */
    public Double get(SloThreshold threshold) {
        return thresholds.get(threshold);
    }

    /** Only the thresholds judged per step of a capacity run. */
    public Slo perStep() {
        Map<SloThreshold, Double> picked = new EnumMap<>(SloThreshold.class);
        thresholds.forEach((t, v) -> {
            if (t.judgedPerStep()) {
                picked.put(t, v);
            }
        });
        return new Slo(picked);
    }

    /** The JSON form: JSON key → target. */
    @JsonValue
    public Map<String, Double> asJson() {
        Map<String, Double> json = new LinkedHashMap<>();
        thresholds.forEach((t, v) -> json.put(t.key(), v));
        return json;
    }
}
