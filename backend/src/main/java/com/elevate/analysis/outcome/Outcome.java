package com.elevate.analysis.outcome;

/**
 * How one request ended, judged against what its case expected.
 *
 * <p>Each failing outcome has a {@link #kind()}, the group name the failure summary and
 * the frontend use ({@code accepted_invalid_input}, {@code server_error}, ...).
 */
public enum Outcome {
    /** The answer matched the expectation (2xx for valid input, 4xx for invalid input). */
    HANDLED_CORRECTLY(null, "Handled correctly"),
    /** 2xx for input that should have been rejected: a validation bug. */
    ACCEPTED_INVALID_INPUT("accepted_invalid_input",
            "Accepted invalid input — the API answered 2xx to a request it should have rejected "
                    + "(a validation bug, or an optional field: set the case to expect 2xx)"),
    /** 4xx (not 429) for input that should have succeeded. */
    REJECTED_VALID_INPUT("rejected_valid_input",
            "Rejected valid input — the API answered 4xx to a request that should have succeeded"),
    /** 5xx, whatever was expected. */
    SERVER_ERROR("server_error", "Server error — the server failed"),
    /** 429, whatever was expected. */
    RATE_LIMITED("rate_limited", "Rate limited — too many requests"),
    /** No HTTP response at all (refused, reset, timed out). */
    CONNECTION_ERROR("connection", "Connection failure — no response"),
    /** Website runs (no expectation): a 4xx other than 429. */
    REJECTED("rejected", "Rejected — invalid input"),
    /** Anything else, e.g. a 1xx or an unfollowed 3xx on an API case. */
    UNEXPECTED_STATUS("other", "Unexpected HTTP status");

    private final String kind;
    private final String meaning;

    Outcome(String kind, String meaning) {
        this.kind = kind;
        this.meaning = meaning;
    }

    /** The failure group name, or {@code null} for {@link #HANDLED_CORRECTLY}. */
    public String kind() {
        return kind;
    }

    /** A plain-English explanation of the group. */
    public String meaning() {
        return meaning;
    }

    /** Whether this outcome counts as a failure. */
    public boolean isFailure() {
        return this != HANDLED_CORRECTLY;
    }

    /** Answers that contradict the expectation itself (not load or crashes). */
    public boolean isUnexpectedAnswer() {
        return this == ACCEPTED_INVALID_INPUT || this == REJECTED_VALID_INPUT;
    }

    /** The server failed to answer properly (5xx or no answer). */
    public boolean isServerFailure() {
        return this == SERVER_ERROR || this == CONNECTION_ERROR;
    }

    /** Outcomes that point at a real problem: 5xx, 429 and connection failures. */
    public boolean isProblem() {
        return isServerFailure() || this == RATE_LIMITED;
    }
}
