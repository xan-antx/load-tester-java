package com.elevate.analysis.outcome;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One type of failure: every request that failed the same way with the same status.
 *
 * @param code    HTTP status, or {@code null} for connection failures
 * @param reason  short reason, e.g. {@code expected a 4xx rejection, got 200}
 * @param kind    group name, e.g. {@code accepted_invalid_input}
 * @param meaning plain-English explanation
 * @param total   occurrences in the group
 * @param cases   which cases failed this way, most first
 */
public record FailureGroup(Integer code, String reason, String kind, String meaning, long total,
                           List<CaseOccurrences> cases) {

    /**
     * @param caseName    case label or page path (JSON {@code case})
     * @param occurrences how often it failed this way
     */
    public record CaseOccurrences(@JsonProperty("case") String caseName, long occurrences) {
    }
}
