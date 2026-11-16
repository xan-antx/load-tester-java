package com.elevate.analysis.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.elevate.analysis.slo.Slo;
import com.elevate.analysis.slo.SloValidator;
import com.elevate.exception.BadRequestException;
import com.elevate.model.CapacityPlan;

import tools.jackson.databind.json.JsonMapper;

class CapacityTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final CapacityPlan PLAN = new CapacityPlan(20, 20, 12, 200, true);
    private static final Slo SLO = SloValidator.clean(JSON.readTree("{\"p95_ms\":500,\"max_server_error_rate_pct\":1,\"min_rps\":1000}"));

    private static CapacityPlan clean(String json) {
        return CapacityPlanValidator.clean(JSON.readTree(json));
    }

    @Test
    void planStepsAndTotal() {
        CapacityPlan plan = clean("{\"start_users\":20,\"step_users\":20,\"step_duration_seconds\":12,\"max_users\":200}");
        assertThat(plan.steps()).containsExactly(20, 40, 60, 80, 100, 120, 140, 160, 180, 200);
        assertThat(plan.totalSeconds()).isEqualTo(120);
        assertThat(plan.earlyStop()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "[]|capacity must be an object",
        "{\"start_users\":1.5,\"step_users\":1,\"step_duration_seconds\":5,\"max_users\":5}|capacity.start_users must be a whole number",
        "{\"start_users\":0,\"step_users\":1,\"step_duration_seconds\":5,\"max_users\":5}|capacity.start_users must be between 1 and 1000",
        "{\"start_users\":1,\"step_users\":1,\"step_duration_seconds\":4,\"max_users\":5}|capacity.step_duration_seconds must be between 5 and 300",
        "{\"start_users\":10,\"step_users\":1,\"step_duration_seconds\":5,\"max_users\":5}|capacity.max_users must be at least start_users",
        "{\"start_users\":1,\"step_users\":1,\"step_duration_seconds\":5,\"max_users\":5,\"early_stop\":\"yes\"}|capacity.early_stop must be true or false",
        "{\"start_users\":1,\"step_users\":1,\"step_duration_seconds\":10,\"max_users\":40}|40 steps x 10 s = 400 s; a capacity run may last at most 300 s (use fewer or shorter steps)",
    })
    void rejectsBadPlans(String json, String message) {
        assertThatThrownBy(() -> clean(json)).isInstanceOf(BadRequestException.class).hasMessage(message);
    }

    @Test
    void spawnRateMustReachEachStepWithinHalfOfIt() {
        assertThat(CapacityPlanValidator.rampProblem(PLAN, 20)).isEmpty();
        assertThat(CapacityPlanValidator.rampProblem(PLAN, 2)).hasValue(
                "spawn_rate 2/s needs 10 s to add 20 users, more than half of a 12 s step; "
                        + "use a spawn rate of at least 4 (max 20) or longer steps");
    }

    @Test
    void capacityRunsNeedAPerStepSlo() {
        assertThatThrownBy(() -> CapacityPlanValidator.requireStepSlo(null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> CapacityPlanValidator.requireStepSlo(SloValidator.clean(JSON.readTree("{\"min_rps\":1}"))))
                .hasMessageStartingWith("A capacity run needs an SLO");
    }

    private static StepRecord step(int index, int users, double p95, long serverErrors, int seconds) {
        return new StepRecord(index, users, users, seconds, 1000, serverErrors, 1000L * 100_000, p95, serverErrors, 0, 0, 0, 0);
    }

    @Test
    void findsMaxSustainableAndBreakingPoint() {
        List<StepRecord> steps = List.of(step(0, 20, 150, 0, 12), step(1, 40, 160, 0, 12), step(2, 60, 550, 0, 12),
                step(3, 80, 900, 0, 1));
        CapacityResult r = CapacityEvaluator.evaluate(steps, PLAN, SLO);
        assertThat(r.maxSustainableUsers()).isEqualTo(40);
        assertThat(r.breakingPointUsers()).isEqualTo(60);
        assertThat(r.breakingReason()).isEqualTo("p95 response time 550 ms > 500 ms");
        assertThat(r.testedUpToUsers()).isEqualTo(60);
        assertThat(r.stoppedEarly()).isTrue();
        assertThat(r.headline()).isEqualTo("Max sustainable: 40 users at p95 < 500 ms, server errors < 1%");
        assertThat(r.steps().getLast().complete()).as("one second of a 12 s step is partial").isFalse();
        assertThat(r.steps().getFirst().rps()).isEqualTo(83.33);
        assertThat(r.steps().getFirst().avgMs()).isEqualTo(100.0);
    }

    @Test
    void breaksAtTheFirstStep() {
        CapacityResult r = CapacityEvaluator.evaluate(List.of(step(0, 20, 150, 50, 12)), PLAN, SLO);
        assertThat(r.headline()).isEqualTo("Breaks already at the first step (20 users)");
        assertThat(r.breakingReason()).contains("Server errors (5xx + connection) 5 % > 1 %");
    }

    @Test
    void noBreakingPointAndNoCompleteStep() {
        CapacityPlan two = new CapacityPlan(20, 20, 12, 40, true);
        assertThat(CapacityEvaluator.evaluate(List.of(step(0, 20, 100, 0, 12), step(1, 40, 120, 0, 12)), two, SLO).headline())
                .isEqualTo("No breaking point within 40 users (p95 < 500 ms, server errors < 1% held at every step)");
        assertThat(CapacityEvaluator.evaluate(List.of(step(0, 20, 100, 0, 2)), two, SLO).headline())
                .isEqualTo("No complete step to judge");
        assertThat(CapacityEvaluator.evaluate(List.of(step(0, 20, 100, 0, 12)), two, null).headline())
                .isEqualTo("No SLO to judge the steps against");
    }

    @Test
    void earlyStopJudgeUsesThePerStepSlo() {
        assertThat(CapacityEvaluator.breaksSlo(step(0, 20, 600, 0, 12), SLO)).isTrue();
        assertThat(CapacityEvaluator.breaksSlo(step(0, 20, 100, 0, 12), SLO)).as("min_rps is not judged per step").isFalse();
    }
}
