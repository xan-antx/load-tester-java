package com.elevate.analysis.capacity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.elevate.analysis.slo.Slo;
import com.elevate.analysis.slo.SloEvaluator;
import com.elevate.analysis.slo.SloThreshold;
import com.elevate.analysis.slo.SloVerdict;
import com.elevate.analysis.stats.Numbers;
import com.elevate.model.CapacityPlan;

/**
 * Judges each step of a capacity run against the per-step part of the SLO and finds the
 * answer: the <b>max sustainable users</b> (highest step that passed, every lower step
 * passing too) and the <b>breaking point</b> (first step that failed).
 */
public final class CapacityEvaluator {

    /** A step needs this share of its seconds to count as complete. */
    public static final double COMPLETE_SHARE = 0.75;

    private static final Map<SloThreshold, String> CRITERIA_WORDS = Map.of(
            SloThreshold.P95_MS, "p95 < %s ms",
            SloThreshold.AVG_MS, "average < %s ms",
            SloThreshold.MAX_SERVER_ERROR_RATE_PCT, "server errors < %s%%",
            SloThreshold.MAX_UNEXPECTED_OUTCOME_RATE_PCT, "unexpected answers < %s%%");

    private CapacityEvaluator() {
    }

    /**
     * Whether a just-finished step missed the SLO (the engine uses this to stop early).
     *
     * @param record the step
     * @param slo    the run's SLO
     * @return {@code true} if the step's verdict is FAIL
     */
    public static boolean breaksSlo(StepRecord record, Slo slo) {
        SloVerdict verdict = SloEvaluator.evaluate(slo.perStep(), record.metrics());
        return verdict != null && SloVerdict.FAIL.equals(verdict.verdict());
    }

    /**
     * @param records the steps the run recorded, in order
     * @param plan    the plan
     * @param slo     the run's SLO
     * @return the capacity answer
     */
    public static CapacityResult evaluate(List<StepRecord> records, CapacityPlan plan, Slo slo) {
        Slo judged = slo == null ? null : slo.perStep();
        List<CapacityStep> steps = new ArrayList<>();
        for (StepRecord r : records) {
            steps.add(step(r, plan, judged));
        }
        List<CapacityStep> complete = steps.stream().filter(CapacityStep::complete).toList();
        CapacityStep sustainable = null;
        CapacityStep breaking = null;
        for (CapacityStep s : complete) {
            if (SloVerdict.FAIL.equals(s.verdict())) {
                breaking = s;
                break;
            }
            if (SloVerdict.PASS.equals(s.verdict())) {
                sustainable = s;
            }
        }
        Integer tested = complete.isEmpty() ? null : complete.getLast().plannedUsers();
        boolean hasSlo = judged != null && !judged.isEmpty();
        String criteria = hasSlo ? criteria(judged) : "no SLO";
        return new CapacityResult(steps, criteria,
                sustainable == null ? null : sustainable.plannedUsers(),
                breaking == null ? null : breaking.plannedUsers(),
                breaking == null ? null : String.join("; ", breaking.failedChecks()),
                tested, plan.steps().size(), complete.size() < plan.steps().size(),
                headline(hasSlo, complete.isEmpty(), sustainable, breaking, tested, criteria));
    }

    private static CapacityStep step(StepRecord r, CapacityPlan plan, Slo judged) {
        SloVerdict verdict = judged == null ? null : SloEvaluator.evaluate(judged, r.metrics());
        List<String> failedChecks = verdict == null ? List.of() : verdict.checks().stream()
                .filter(c -> Boolean.FALSE.equals(c.passed()))
                .map(CapacityEvaluator::describe)
                .toList();
        long requests = r.requests();
        return new CapacityStep(r.index(), r.plannedUsers(), r.users(), r.seconds(),
                r.seconds() >= COMPLETE_SHARE * plan.stepDurationSeconds(), requests,
                Numbers.round(requests / (double) Math.max(r.seconds(), 1), 2),
                Numbers.round(r.p95Ms(), 2), r.avgMs(), Numbers.percent(r.failures(), requests, 2),
                Numbers.percent(r.serverErrors() + r.connectionErrors(), requests, 2),
                Numbers.percent(r.acceptedInvalid() + r.rejectedValid(), requests, 2),
                Numbers.percent(r.rateLimited(), requests, 2),
                verdict == null ? null : verdict.verdict(), failedChecks);
    }

    private static String describe(SloVerdict.Check check) {
        String sign = "<=".equals(check.comparison()) ? ">" : "<";
        return check.label() + " " + Numbers.plain(check.actual()) + " " + check.unit() + " " + sign + " "
                + Numbers.plain(check.target()) + " " + check.unit();
    }

    private static String criteria(Slo judged) {
        return judged.thresholds().entrySet().stream()
                .map(e -> String.format(CRITERIA_WORDS.get(e.getKey()), Numbers.plain(e.getValue())))
                .collect(Collectors.joining(", "));
    }

    private static String headline(boolean hasSlo, boolean noCompleteStep, CapacityStep sustainable,
                                   CapacityStep breaking, Integer tested, String criteria) {
        if (!hasSlo) {
            return "No SLO to judge the steps against";
        }
        if (noCompleteStep) {
            return "No complete step to judge";
        }
        if (breaking != null && sustainable != null) {
            return "Max sustainable: " + sustainable.plannedUsers() + " users at " + criteria;
        }
        if (breaking != null) {
            return "Breaks already at the first step (" + breaking.plannedUsers() + " users)";
        }
        return "No breaking point within " + tested + " users (" + criteria + " held at every step)";
    }
}
