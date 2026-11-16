package com.elevate.analysis.headers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.elevate.exception.BadRequestException;

import tools.jackson.databind.json.JsonMapper;

class RequestHeadersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void validHeadersAreTrimmedAndNamesSorted() {
        RequestHeaders h = RequestHeaders.clean(JSON.readTree("{\"X-Trace\":\" 1 \",\"Authorization\":\"Bearer abc\"}"));
        assertThat(h.values()).containsEntry("X-Trace", "1");
        assertThat(h.names()).containsExactly("Authorization", "X-Trace");
        assertThat(RequestHeaders.clean(null)).isSameAs(RequestHeaders.NONE);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "[]|headers must be an object of name: value pairs",
        "{\"Bad Name\":\"x\"}|a header name may only contain letters, digits and - _ . (no spaces)",
        "{\"Host\":\"x\"}|header 'Host' is set by the HTTP client and cannot be overridden",
        "{\"X\":1}|the value of header 'X' must be text",
        "{\"X\":\"a\\nb\"}|the value of header 'X' must be a single line",
    })
    void rejectsBadHeadersWithoutEchoingValues(String json, String message) {
        assertThatThrownBy(() -> RequestHeaders.clean(JSON.readTree(json)))
                .isInstanceOf(BadRequestException.class).hasMessage(message);
    }

    @Test
    void limitsCountAndLength() {
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i < 21; i++) {
            many.append(i == 0 ? "" : ",").append("\"H").append(i).append("\":\"v\"");
        }
        assertThatThrownBy(() -> RequestHeaders.clean(JSON.readTree(many.append("}").toString())))
                .hasMessage("at most 20 headers are allowed");
        assertThatThrownBy(() -> RequestHeaders.clean(JSON.readTree("{\"X\":\"" + "a".repeat(4097) + "\"}")))
                .hasMessage("the value of header 'X' is too long");
    }

    @Test
    void redactHidesEveryValue() {
        RequestHeaders h = RequestHeaders.clean(JSON.readTree("{\"Authorization\":\"Bearer secret\"}"));
        assertThat(h.redact("failed with Bearer secret")).isEqualTo("failed with ***");
        assertThat(h.redact(null)).isNull();
    }
}
