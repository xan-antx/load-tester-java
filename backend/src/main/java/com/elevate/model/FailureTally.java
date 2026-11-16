package com.elevate.model;

import com.elevate.analysis.outcome.Outcome;
import com.elevate.analysis.stats.FailureCount;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * One failure message of a case and how often it happened (an embeddable record stored in
 * the {@code case_failure} collection table).
 *
 * @param outcome     the failing outcome
 * @param statusCode  HTTP status, or {@code null} for no response
 * @param reason      short reason
 * @param message     full message
 * @param occurrences count
 */
@Embeddable
public record FailureTally(
        @Enumerated(EnumType.STRING) @Column(length = 32) Outcome outcome,
        Integer statusCode,
        @Column(length = 500) String reason,
        @Column(length = 1000) String message,
        long occurrences) {

    /** The stored form of a failure count. */
    public static FailureTally from(FailureCount f) {
        return new FailureTally(f.outcome(), f.statusCode(), f.reason(), f.message(), f.occurrences());
    }

    /** Back to the analysis form. */
    public FailureCount toFailureCount(String caseName, String method) {
        return new FailureCount(caseName, method, outcome, statusCode, reason, message, occurrences);
    }
}
