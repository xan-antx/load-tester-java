package com.elevate.analysis.slo;

import java.util.ArrayList;
import java.util.List;

import com.elevate.analysis.stats.Numbers;

/**
 * Compares measurements with an SLO: PASS when every measured threshold holds, FAIL when
 * any does not, and no verdict when nothing could be measured.
 */
public final class SloEvaluator {

    private SloEvaluator() {
    }

    /**
     * @param slo     the thresholds, or {@code null}
     * @param metrics what was measured
     * @return the verdict, or {@code null} when there is no SLO
     */
    public static SloVerdict evaluate(Slo slo, RunMetrics metrics) {
        if (slo == null || slo.isEmpty()) {
            return null;
        }
        List<SloVerdict.Check> checks = new ArrayList<>();
        slo.thresholds().forEach((threshold, target) -> {
            Double actual = threshold.measured(metrics);
            Boolean passed = actual == null ? null : threshold.isMet(actual, target);
            checks.add(new SloVerdict.Check(threshold.key(), threshold.label(), threshold.unit(),
                    threshold.comparison(), target, Numbers.round(actual, 2), passed));
        });
        int failed = (int) checks.stream().filter(c -> Boolean.FALSE.equals(c.passed())).count();
        int passed = (int) checks.stream().filter(c -> Boolean.TRUE.equals(c.passed())).count();
        String verdict = failed > 0 ? SloVerdict.FAIL : passed > 0 ? SloVerdict.PASS : null;
        return new SloVerdict(verdict, List.copyOf(checks), passed, failed, checks.size() - passed - failed);
    }
}
