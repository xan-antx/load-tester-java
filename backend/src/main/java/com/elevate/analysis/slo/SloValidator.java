package com.elevate.analysis.slo;

import java.util.EnumMap;
import java.util.Map;
import java.util.TreeSet;

import com.elevate.analysis.stats.Numbers;
import com.elevate.exception.BadRequestException;

import tools.jackson.databind.JsonNode;

/** Validates the {@code slo} object of a start request. */
public final class SloValidator {

    private SloValidator() {
    }

    /**
     * @param raw the {@code slo} JSON value, or {@code null}
     * @return the thresholds that were set, or {@code null} when none were
     * @throws BadRequestException naming the bad field
     */
    public static Slo clean(JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isMissingNode() || (raw.isObject() && raw.isEmpty())) {
            return null;
        }
        if (!raw.isObject()) {
            throw new BadRequestException("slo must be an object of thresholds");
        }
        TreeSet<String> unknown = new TreeSet<>();
        raw.propertyNames().forEach(key -> {
            if (SloThreshold.fromKey(key).isEmpty()) {
                unknown.add(key);
            }
        });
        if (!unknown.isEmpty()) {
            throw new BadRequestException("unknown SLO threshold(s): " + String.join(", ", unknown));
        }
        Map<SloThreshold, Double> thresholds = new EnumMap<>(SloThreshold.class);
        for (Map.Entry<String, JsonNode> entry : raw.properties()) {
            JsonNode value = entry.getValue();
            if (value.isNull()) {
                continue;
            }
            SloThreshold threshold = SloThreshold.fromKey(entry.getKey()).orElseThrow();
            if (!value.isNumber()) {
                throw new BadRequestException("slo." + threshold.key() + " must be a number");
            }
            double number = value.doubleValue();
            if (number < threshold.low() || number > threshold.high()) {
                throw new BadRequestException("slo." + threshold.key() + " must be between "
                        + Numbers.plain(threshold.low()) + " and " + Numbers.plain(threshold.high()) + " "
                        + threshold.unit());
            }
            thresholds.put(threshold, number);
        }
        return thresholds.isEmpty() ? null : new Slo(thresholds);
    }
}
