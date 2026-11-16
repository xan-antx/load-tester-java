package com.elevate.dto;

import java.util.List;

import com.elevate.analysis.capacity.CapacityResult;
import com.elevate.analysis.outcome.FailureGroup;
import com.elevate.analysis.outcome.OutcomeSummary;
import com.elevate.analysis.slo.SloVerdict;

/**
 * What the backend concludes from a run's results; part of every run view.
 *
 * @param failureSummary failures grouped by kind and status
 * @param problemCount   5xx + 429 + connection failures
 * @param problemRate    the same in percent
 * @param outcomeSummary outcomes against expectations (API runs)
 * @param sloVerdict     PASS / FAIL once completed (fixed runs with an SLO)
 * @param capacity       the capacity answer (capacity runs)
 */
public record RunAnalysis(List<FailureGroup> failureSummary, long problemCount, Double problemRate,
                          OutcomeSummary outcomeSummary, SloVerdict sloVerdict, CapacityResult capacity) {
}
