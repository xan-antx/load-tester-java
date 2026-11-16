package com.elevate.analysis.stats;

import java.util.List;

/**
 * The measured results of a run, or of several runs merged (a split group).
 *
 * @param cases          one measurement per case, in the order the cases were configured
 * @param failures       failure tallies
 * @param timeline       one sample per second, oldest first
 * @param elapsedSeconds how long the load ran (for requests per second)
 */
public record RunResults(List<CaseMeasurement> cases, List<FailureCount> failures,
                         List<TimelineSample> timeline, double elapsedSeconds) {

    /** Results of a run that has not recorded anything. */
    public static RunResults empty() {
        return new RunResults(List.of(), List.of(), List.of(), 0);
    }

    /** Whether any results were recorded (a run cancelled before it started has none). */
    public boolean isEmpty() {
        return cases.isEmpty();
    }

    /** Defensive copies: callers cannot change the lists. */
    public RunResults {
        cases = List.copyOf(cases);
        failures = List.copyOf(failures);
        timeline = List.copyOf(timeline);
    }
}
