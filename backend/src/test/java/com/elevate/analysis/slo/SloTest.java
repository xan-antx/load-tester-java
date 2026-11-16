package com.elevate.analysis.slo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.elevate.exception.BadRequestException;

import tools.jackson.databind.json.JsonMapper;

class SloTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Slo clean(String json) {
        return SloValidator.clean(json == null ? null : JSON.readTree(json));
    }

    @Test
    void emptyOrMissingMeansNoSlo() {
        assertThat(clean(null)).isNull();
        assertThat(clean("{}")).isNull();
        assertThat(clean("{\"p95_ms\":null}")).isNull();
    }

    @Test
    void keepsOnlyTheThresholdsThatWereSet() {
        Slo slo = clean("{\"p95_ms\":500,\"max_server_error_rate_pct\":1,\"avg_ms\":null}");
        assertThat(slo.asJson()).containsOnlyKeys("p95_ms", "max_server_error_rate_pct");
        assertThat(slo.get(SloThreshold.P95_MS)).isEqualTo(500.0);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "[1]|slo must be an object of thresholds",
        "{\"p99\":1,\"foo\":2}|unknown SLO threshold(s): foo, p99",
        "{\"p95_ms\":\"fast\"}|slo.p95_ms must be a number",
        "{\"p95_ms\":true}|slo.p95_ms must be a number",
        "{\"p95_ms\":0}|slo.p95_ms must be between 1 and 600000 ms",
        "{\"max_server_error_rate_pct\":101}|slo.max_server_error_rate_pct must be between 0 and 100 %",
        "{\"min_rps\":-1}|slo.min_rps must be between 0 and 100000 req/s",
    })
    void rejectsBadThresholdsNamingTheField(String json, String message) {
        assertThatThrownBy(() -> clean(json)).isInstanceOf(BadRequestException.class).hasMessage(message);
    }

    @Test
    void passWhenEveryMeasuredThresholdHolds() {
        Slo slo = clean("{\"p95_ms\":500,\"max_server_error_rate_pct\":1,\"min_rps\":5}");
        SloVerdict v = SloEvaluator.evaluate(slo, new RunMetrics(150.0, 100.0, 11.2, 0.0, null));
        assertThat(v.verdict()).isEqualTo("PASS");
        assertThat(v.passed()).isEqualTo(3);
        assertThat(v.checks()).extracting(SloVerdict.Check::comparison).containsExactly("<=", "<=", ">=");
    }

    @Test
    void failWhenAnyThresholdIsMissed() {
        Slo slo = clean("{\"p95_ms\":500,\"max_server_error_rate_pct\":1}");
        SloVerdict v = SloEvaluator.evaluate(slo, new RunMetrics(150.0, 100.0, 11.0, 6.234, null));
        assertThat(v.verdict()).isEqualTo("FAIL");
        assertThat(v.failed()).isEqualTo(1);
        assertThat(v.checks().get(1).actual()).isEqualTo(6.23);
        assertThat(v.checks().get(1).passed()).isFalse();
    }

    @Test
    void unmeasurableThresholdsGiveNoVerdict() {
        Slo slo = clean("{\"max_unexpected_outcome_rate_pct\":5}");
        SloVerdict v = SloEvaluator.evaluate(slo, new RunMetrics(150.0, 100.0, 11.0, 0.0, null));
        assertThat(v.verdict()).isNull();
        assertThat(v.notMeasured()).isEqualTo(1);
        assertThat(SloEvaluator.evaluate(null, new RunMetrics(1.0, 1.0, 1.0, 0.0, 0.0))).isNull();
    }

    @Test
    void perStepSloLeavesThroughputOut() {
        Slo slo = clean("{\"p95_ms\":500,\"min_rps\":5}");
        assertThat(slo.perStep().asJson()).containsOnlyKeys("p95_ms");
    }
}
