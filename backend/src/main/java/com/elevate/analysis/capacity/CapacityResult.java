package com.elevate.analysis.capacity;

import java.util.List;

/**
 * The answer of a capacity run.
 *
 * @param steps               every recorded step with its verdict
 * @param criteria            the per-step SLO in words, e.g. {@code p95 < 500 ms}
 * @param maxSustainableUsers highest step that passed with every lower step passing too
 * @param breakingPointUsers  first step that failed, or {@code null}
 * @param breakingReason      which thresholds that step missed
 * @param testedUpToUsers     highest complete step
 * @param plannedSteps        steps in the plan
 * @param stoppedEarly        fewer steps completed than planned
 * @param headline            one-sentence answer
 */
public record CapacityResult(List<CapacityStep> steps, String criteria, Integer maxSustainableUsers,
                             Integer breakingPointUsers, String breakingReason, Integer testedUpToUsers,
                             int plannedSteps, boolean stoppedEarly, String headline) {
}
