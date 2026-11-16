package com.elevate.analysis.capacity;

import java.util.Optional;

import com.elevate.analysis.slo.Slo;
import com.elevate.analysis.stats.Numbers;
import com.elevate.exception.BadRequestException;
import com.elevate.model.CapacityPlan;

import tools.jackson.databind.JsonNode;

/**
 * Validates a capacity plan against the same limits as the Python version: users 1–1000,
 * steps of 5–300 s, at most 300 s in total, and a spawn rate fast enough to reach each
 * step's load within the first half of the step.
 */
public final class CapacityPlanValidator {

    /** Highest load of a capacity run. */
    public static final int MAX_USERS = 1000;
    /** Longest capacity run. */
    public static final int MAX_TOTAL_SECONDS = 300;
    /** Shortest step. */
    public static final int MIN_STEP_SECONDS = 5;
    /** Highest spawn rate. */
    public static final int MAX_SPAWN_RATE = 20;

    private CapacityPlanValidator() {
    }

    /**
     * @param raw the {@code capacity} JSON object
     * @return the plan
     * @throws BadRequestException naming the bad field
     */
    public static CapacityPlan clean(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new BadRequestException("capacity must be an object");
        }
        int start = wholeNumber(raw, "start_users", 1, MAX_USERS);
        int step = wholeNumber(raw, "step_users", 1, MAX_USERS);
        int seconds = wholeNumber(raw, "step_duration_seconds", MIN_STEP_SECONDS, MAX_TOTAL_SECONDS);
        int top = wholeNumber(raw, "max_users", 1, MAX_USERS);
        if (top < start) {
            throw new BadRequestException("capacity.max_users must be at least start_users");
        }
        JsonNode earlyStop = raw.get("early_stop");
        if (earlyStop != null && !earlyStop.isBoolean()) {
            throw new BadRequestException("capacity.early_stop must be true or false");
        }
        CapacityPlan plan = new CapacityPlan(start, step, seconds, top, earlyStop == null || earlyStop.booleanValue());
        int steps = plan.steps().size();
        if (plan.totalSeconds() > MAX_TOTAL_SECONDS) {
            throw new BadRequestException(steps + " steps x " + seconds + " s = " + plan.totalSeconds()
                    + " s; a capacity run may last at most " + MAX_TOTAL_SECONDS + " s (use fewer or shorter steps)");
        }
        return plan;
    }

    /**
     * The spawn rate must add a step's users within half of the step, or the step is
     * measured at a lower load than planned.
     *
     * @param plan      the plan
     * @param spawnRate users started per second
     * @return a message when the rate is too slow
     */
    public static Optional<String> rampProblem(CapacityPlan plan, int spawnRate) {
        int biggestJump = Math.max(plan.startUsers(), plan.steps().size() > 1 ? plan.stepUsers() : 0);
        double seconds = (double) biggestJump / spawnRate;
        if (seconds <= plan.stepDurationSeconds() / 2.0) {
            return Optional.empty();
        }
        int need = (int) Math.ceil(2.0 * biggestJump / plan.stepDurationSeconds());
        return Optional.of("spawn_rate " + spawnRate + "/s needs " + Numbers.plain(seconds) + " s to add "
                + biggestJump + " users, more than half of a " + plan.stepDurationSeconds()
                + " s step; use a spawn rate of at least " + need + " (max " + MAX_SPAWN_RATE + ") or longer steps");
    }

    /**
     * A capacity run is judged step by step, so it needs at least one per-step threshold.
     *
     * @param slo the run's SLO, or {@code null}
     * @throws BadRequestException when none of p95, average, server-error or unexpected-answer limits is set
     */
    public static void requireStepSlo(Slo slo) {
        if (slo == null || slo.perStep().isEmpty()) {
            throw new BadRequestException("A capacity run needs an SLO to judge each step: set p95_ms, avg_ms, "
                    + "max_server_error_rate_pct or max_unexpected_outcome_rate_pct.");
        }
    }

    private static int wholeNumber(JsonNode raw, String key, int low, int high) {
        JsonNode value = raw.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new BadRequestException("capacity." + key + " must be a whole number");
        }
        int number = value.intValue();
        if (number < low || number > high) {
            throw new BadRequestException("capacity." + key + " must be between " + low + " and " + high);
        }
        return number;
    }
}
