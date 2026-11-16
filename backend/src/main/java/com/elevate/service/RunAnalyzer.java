package com.elevate.service;

import java.util.List;

import com.elevate.analysis.capacity.CapacityEvaluator;
import com.elevate.analysis.capacity.CapacityResult;
import com.elevate.analysis.capacity.StepRecord;
import com.elevate.analysis.outcome.FailureSummarizer;
import com.elevate.analysis.outcome.OutcomeAnalysis;
import com.elevate.analysis.outcome.OutcomeSummary;
import com.elevate.analysis.slo.RunMetrics;
import com.elevate.analysis.slo.Slo;
import com.elevate.analysis.slo.SloEvaluator;
import com.elevate.analysis.slo.SloVerdict;
import com.elevate.analysis.stats.RunResults;
import com.elevate.analysis.stats.StatsTable;
import com.elevate.dto.RunAnalysis;
import com.elevate.model.CapacityPlan;
import com.elevate.model.RunStatus;

/**
 * Draws the conclusions of a run from its results: failure groups, problem rate, outcome
 * summary, and either the SLO verdict (fixed runs, once completed) or the capacity answer
 * (capacity runs, which are judged step by step instead of as a whole).
 */
public final class RunAnalyzer {

    private RunAnalyzer() {
    }

    /**
     * @param results         measured results
     * @param stats           their statistics table (or {@code null} without results)
     * @param status          the run's status
     * @param slo             thresholds, or {@code null}
     * @param hasExpectations whether the run's cases carry expectations
     * @param plan            capacity plan, or {@code null}
     * @param steps           capacity step records
     * @return the analysis
     */
    public static RunAnalysis analyze(RunResults results, StatsTable stats, RunStatus status, Slo slo,
                                      boolean hasExpectations, CapacityPlan plan, List<StepRecord> steps) {
        Long total = stats == null ? null : stats.totalRequests();
        OutcomeAnalysis.ProblemRate problem = OutcomeAnalysis.problemRate(results.failures(), total);
        OutcomeSummary outcomes = OutcomeAnalysis.summarize(results.failures(), total, hasExpectations);
        SloVerdict verdict = null;
        CapacityResult capacity = null;
        if (plan != null) {
            capacity = steps.isEmpty() ? null : CapacityEvaluator.evaluate(steps, plan, slo);
        } else if (slo != null && status == RunStatus.COMPLETED) {
            verdict = SloEvaluator.evaluate(slo, metrics(results, stats, total, outcomes));
        }
        return new RunAnalysis(FailureSummarizer.summarize(results.failures()), problem.problemCount(),
                problem.problemRate(), outcomes, verdict, capacity);
    }

    private static RunMetrics metrics(RunResults results, StatsTable stats, Long total, OutcomeSummary outcomes) {
        if (stats == null) {
            return new RunMetrics(null, null, null, null, null);
        }
        return new RunMetrics(stats.aggregated().p95Ms(), stats.aggregated().averageMs(),
                stats.aggregated().requestsPerSecond(), OutcomeAnalysis.serverErrorRatePct(results.failures(), total),
                outcomes == null ? null : outcomes.unexpectedOutcomeRatePct());
    }
}
