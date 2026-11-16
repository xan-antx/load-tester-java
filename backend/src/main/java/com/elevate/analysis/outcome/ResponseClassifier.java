package com.elevate.analysis.outcome;

import java.util.Map;

import com.elevate.model.Expectation;

/**
 * Decides whether one response is what a correct API should have answered, and words
 * the failure the way the Python version (and so the frontend) expects.
 *
 * <p>With an expectation (API runs): a request succeeds when the answer matches it — 2xx
 * for valid input, 4xx (not 429) for invalid input; 429, 5xx and no answer never match.
 * Without one (website runs) Locust's default rule applies: any status of 400 or more fails.
 */
public final class ResponseClassifier {

    /** Status code used when there was no HTTP response at all. */
    public static final int NO_RESPONSE = 0;

    private static final int MAX_REASON_LENGTH = 120;

    private static final Map<Integer, String> REASON_PHRASES = Map.ofEntries(
            Map.entry(400, "BAD REQUEST"), Map.entry(401, "UNAUTHORIZED"), Map.entry(403, "FORBIDDEN"),
            Map.entry(404, "NOT FOUND"), Map.entry(405, "METHOD NOT ALLOWED"), Map.entry(408, "REQUEST TIMEOUT"),
            Map.entry(409, "CONFLICT"), Map.entry(410, "GONE"), Map.entry(413, "PAYLOAD TOO LARGE"),
            Map.entry(415, "UNSUPPORTED MEDIA TYPE"), Map.entry(422, "UNPROCESSABLE ENTITY"),
            Map.entry(429, "TOO MANY REQUESTS"), Map.entry(500, "INTERNAL SERVER ERROR"),
            Map.entry(502, "BAD GATEWAY"), Map.entry(503, "SERVICE UNAVAILABLE"), Map.entry(504, "GATEWAY TIMEOUT"));

    private ResponseClassifier() {
    }

    /**
     * Classifies one response.
     *
     * @param status      HTTP status, or {@link #NO_RESPONSE}
     * @param expectation the case's expectation, or {@code null} for website pages
     * @return the outcome
     */
    public static Outcome classify(int status, Expectation expectation) {
        if (status == NO_RESPONSE) {
            return Outcome.CONNECTION_ERROR;
        }
        if (expectation == null) {
            return classifyWithoutExpectation(status);
        }
        if (status == 429) {
            return Outcome.RATE_LIMITED;
        }
        if (status >= 500) {
            return Outcome.SERVER_ERROR;
        }
        boolean is2xx = status >= 200 && status < 300;
        boolean is4xx = status >= 400 && status < 500;
        return switch (expectation) {
            case ACCEPT -> is2xx ? Outcome.HANDLED_CORRECTLY : is4xx ? Outcome.REJECTED_VALID_INPUT : Outcome.UNEXPECTED_STATUS;
            case REJECT -> is4xx ? Outcome.HANDLED_CORRECTLY : is2xx ? Outcome.ACCEPTED_INVALID_INPUT : Outcome.UNEXPECTED_STATUS;
        };
    }

    private static Outcome classifyWithoutExpectation(int status) {
        if (status < 400) {
            return Outcome.HANDLED_CORRECTLY;
        }
        if (status == 429) {
            return Outcome.RATE_LIMITED;
        }
        return status >= 500 ? Outcome.SERVER_ERROR : Outcome.REJECTED;
    }

    /**
     * The words recorded for a failed request.
     *
     * @param outcome     a failing outcome
     * @param status      HTTP status, or {@link #NO_RESPONSE}
     * @param expectation the case's expectation, or {@code null}
     * @param caseName    the case label or page path
     * @param error       the connection error text when there was no response
     * @return a short reason and the full message (the {@code Error} column of the failures CSV)
     */
    public static FailureDetail describe(Outcome outcome, int status, Expectation expectation,
                                         String caseName, String error) {
        if (outcome == Outcome.CONNECTION_ERROR) {
            String reason = truncate("no response (" + (error == null ? "connection failed" : error) + ")");
            return new FailureDetail(reason, "connection: " + reason);
        }
        if (expectation == null) {
            String phrase = REASON_PHRASES.getOrDefault(status, "HTTP " + status);
            String side = status >= 500 ? "Server" : "Client";
            return new FailureDetail(phrase, status + " " + side + " Error: " + phrase + " for url: " + caseName);
        }
        String wanted = expectation == Expectation.ACCEPT ? "a 2xx success" : "a 4xx rejection";
        String category = outcome == Outcome.UNEXPECTED_STATUS ? "unexpected_status" : outcome.kind();
        String reason = "expected " + wanted + ", got " + status;
        return new FailureDetail(reason, category + " (" + status + "): " + reason);
    }

    /**
     * The meaning of a failure group; 401 and 403 get their own hint.
     *
     * @param outcome the group's outcome
     * @param status  the group's status code, or {@code null}
     * @return the plain-English meaning
     */
    public static String meaningOf(Outcome outcome, Integer status) {
        if (outcome == Outcome.REJECTED && status != null && (status == 401 || status == 403)) {
            return "Rejected — not authorised (check the request headers)";
        }
        return outcome.meaning();
    }

    private static String truncate(String text) {
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }

    /**
     * How a failure is worded.
     *
     * @param reason  short reason shown in the failure summary
     * @param message the full error message
     */
    public record FailureDetail(String reason, String message) {
    }
}
