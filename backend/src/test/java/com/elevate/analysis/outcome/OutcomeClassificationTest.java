package com.elevate.analysis.outcome;

import static com.elevate.model.Expectation.ACCEPT;
import static com.elevate.model.Expectation.REJECT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.elevate.analysis.stats.FailureCount;
import com.elevate.model.Expectation;

/** Outcome classification, failure grouping, outcome summary and problem rate. */
class OutcomeClassificationTest {

    @ParameterizedTest(name = "{0} with expect={1} -> {2}")
    @CsvSource({
        "200, ACCEPT, HANDLED_CORRECTLY",
        "201, ACCEPT, HANDLED_CORRECTLY",
        "400, ACCEPT, REJECTED_VALID_INPUT",
        "400, REJECT, HANDLED_CORRECTLY",
        "422, REJECT, HANDLED_CORRECTLY",
        "200, REJECT, ACCEPTED_INVALID_INPUT",
        "429, REJECT, RATE_LIMITED",
        "429, ACCEPT, RATE_LIMITED",
        "500, REJECT, SERVER_ERROR",
        "503, ACCEPT, SERVER_ERROR",
        "0, ACCEPT, CONNECTION_ERROR",
        "302, ACCEPT, UNEXPECTED_STATUS",
        "101, REJECT, UNEXPECTED_STATUS",
    })
    void classifiesAgainstTheExpectation(int status, Expectation expect, Outcome outcome) {
        assertThat(ResponseClassifier.classify(status, expect)).isEqualTo(outcome);
    }

    @ParameterizedTest(name = "website {0} -> {1}")
    @CsvSource({"200, HANDLED_CORRECTLY", "301, HANDLED_CORRECTLY", "404, REJECTED", "403, REJECTED",
        "429, RATE_LIMITED", "500, SERVER_ERROR", "0, CONNECTION_ERROR"})
    void websitePagesUseLocustsRule(int status, Outcome outcome) {
        assertThat(ResponseClassifier.classify(status, null)).isEqualTo(outcome);
    }

    @Test
    void failureMessagesMatchThePythonWording() {
        assertThat(ResponseClassifier.describe(Outcome.ACCEPTED_INVALID_INPUT, 200, REJECT, "c", null).message())
                .isEqualTo("accepted_invalid_input (200): expected a 4xx rejection, got 200");
        assertThat(ResponseClassifier.describe(Outcome.UNEXPECTED_STATUS, 302, ACCEPT, "c", null).message())
                .isEqualTo("unexpected_status (302): expected a 2xx success, got 302");
        assertThat(ResponseClassifier.describe(Outcome.CONNECTION_ERROR, 0, ACCEPT, "c", "ConnectException").message())
                .isEqualTo("connection: no response (ConnectException)");
        ResponseClassifier.FailureDetail page = ResponseClassifier.describe(Outcome.REJECTED, 404, null, "/about", null);
        assertThat(page.reason()).isEqualTo("NOT FOUND");
        assertThat(page.message()).isEqualTo("404 Client Error: NOT FOUND for url: /about");
        assertThat(ResponseClassifier.describe(Outcome.SERVER_ERROR, 599, null, "/", null).message())
                .startsWith("599 Server Error: HTTP 599");
    }

    @Test
    void longConnectionErrorsAreTruncated() {
        String reason = ResponseClassifier.describe(Outcome.CONNECTION_ERROR, 0, null, "c", "x".repeat(500)).reason();
        assertThat(reason).hasSize(120);
    }

    @Test
    void notAuthorisedGetsItsOwnMeaning() {
        assertThat(ResponseClassifier.meaningOf(Outcome.REJECTED, 401)).contains("not authorised");
        assertThat(ResponseClassifier.meaningOf(Outcome.REJECTED, 404)).isEqualTo("Rejected — invalid input");
    }

    private static FailureCount failure(String c, Outcome o, Integer code, long n) {
        return new FailureCount(c, "POST", o, code, "r", o + "/" + code + "/" + c, n);
    }

    private final List<FailureCount> failures = List.of(
            failure("very_long_string_username", Outcome.ACCEPTED_INVALID_INPUT, 200, 11),
            failure("wrong_type_expiresInMins", Outcome.SERVER_ERROR, 500, 12),
            failure("very_large_expiresInMins", Outcome.SERVER_ERROR, 500, 9),
            failure("valid_sample", Outcome.RATE_LIMITED, 429, 3),
            failure("missing_username", Outcome.CONNECTION_ERROR, null, 2));

    @Test
    void failuresAreGroupedByKindAndStatusLargestFirst() {
        List<FailureGroup> groups = FailureSummarizer.summarize(failures);
        assertThat(groups).extracting(FailureGroup::kind)
                .containsExactly("server_error", "accepted_invalid_input", "rate_limited", "connection");
        FailureGroup server = groups.getFirst();
        assertThat(server.total()).isEqualTo(21);
        assertThat(server.code()).isEqualTo(500);
        assertThat(server.cases()).extracting(FailureGroup.CaseOccurrences::caseName)
                .containsExactly("wrong_type_expiresInMins", "very_large_expiresInMins");
        assertThat(groups.getLast().code()).isNull();
    }

    @Test
    void outcomeSummaryCountsAgainstTheTotal() {
        OutcomeSummary s = OutcomeAnalysis.summarize(failures, 337L, true);
        assertThat(s.handledCorrectly()).isEqualTo(337 - 37);
        assertThat(s.acceptedInvalidInput()).isEqualTo(11);
        assertThat(s.serverError()).isEqualTo(21);
        assertThat(s.unexpectedOutcomeCount()).isEqualTo(11);
        assertThat(s.unexpectedOutcomeRatePct()).isEqualTo(3.26);
        assertThat(s.serverErrorRatePct()).isEqualTo(6.82);
        assertThat(OutcomeAnalysis.summarize(failures, 337L, false)).isNull();
        assertThat(OutcomeAnalysis.summarize(failures, null, true)).isNull();
    }

    @Test
    void problemRateCountsOnly5xx429AndConnection() {
        OutcomeAnalysis.ProblemRate rate = OutcomeAnalysis.problemRate(failures, 337L);
        assertThat(rate.problemCount()).isEqualTo(26);
        assertThat(rate.problemRate()).isEqualTo(7.7);
        assertThat(OutcomeAnalysis.problemRate(failures, 0L).problemRate()).isNull();
        assertThat(OutcomeAnalysis.serverErrorRatePct(failures, 337L)).isEqualTo(6.82);
    }
}
