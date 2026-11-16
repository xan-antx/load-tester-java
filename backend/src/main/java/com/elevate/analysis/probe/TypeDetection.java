package com.elevate.analysis.probe;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Whether a target looks like an API or a website.
 *
 * @param classification {@code api}, {@code website} or {@code unknown}
 * @param confidence     the winning score, capped at 1.0
 * @param signals        the evidence
 */
public record TypeDetection(String classification, double confidence, List<Signal> signals) {

    /**
     * One piece of evidence. Serialized as a JSON array {@code [name, kind, weight]}, as the
     * Python version wrote its tuples.
     *
     * @param name   e.g. {@code content_type_json}
     * @param kind   {@code api} or {@code website}
     * @param weight how strongly it counts
     */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    @JsonPropertyOrder({"name", "kind", "weight"})
    public record Signal(String name, String kind, double weight) {
    }
}
