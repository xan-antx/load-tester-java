package com.elevate.analysis.edgecase;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/**
 * Whether a user-written case is usable, and how it differs from the sample.
 *
 * @param valid       usable as an edge case
 * @param reason      why not, when not valid
 * @param category    the generator category it matches, or {@code custom}
 * @param description every change, in words
 * @param changes     every top-level difference
 */
public record CustomCaseResult(boolean valid, String reason, String category, String description,
                               List<Change> changes) {

    /** A refusal with its reason. */
    static CustomCaseResult invalid(String reason) {
        return new CustomCaseResult(false, reason, null, null, List.of());
    }

    /**
     * One top-level difference. {@code from}/{@code to} are left out when they do not apply.
     *
     * @param field the field
     * @param kind  {@code removed}, {@code set_null}, {@code type_change}, {@code value_change} or {@code added}
     * @param from  old type name or value
     * @param to    new type name or value
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Change(String field, String kind, @JsonProperty("from") JsonNode from, @JsonProperty("to") JsonNode to) {
    }
}
