package com.elevate.analysis.stats;

import com.elevate.analysis.outcome.Outcome;

/**
 * How often one kind of failure happened for one case.
 *
 * @param caseName    case label or page path
 * @param method      HTTP method
 * @param outcome     the failing outcome
 * @param statusCode  HTTP status, or {@code null} when there was no response
 * @param reason      short reason, e.g. {@code expected a 4xx rejection, got 200}
 * @param message     full message (the failures CSV's Error column)
 * @param occurrences how many requests ended this way
 */
public record FailureCount(String caseName, String method, Outcome outcome, Integer statusCode,
                           String reason, String message, long occurrences) {

    /** Same failure, more occurrences. */
    public FailureCount plus(long more) {
        return new FailureCount(caseName, method, outcome, statusCode, reason, message, occurrences + more);
    }

    /** The identity of a failure: which case, and the exact message. */
    public String key() {
        return caseName + '\u0000' + message;
    }
}
