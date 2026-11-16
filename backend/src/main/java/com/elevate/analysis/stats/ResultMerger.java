package com.elevate.analysis.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merges the results of parallel engine instances (the children of a split run) into one.
 *
 * <p>Nothing is approximated. Counts and sums add up, min and max take the extremes, and
 * HdrHistograms are added, so every percentile of the merged result is the percentile of
 * all requests together. (The Python version averaged each Locust process's p95, weighted
 * by requests, which is only an estimate.) Seconds of the timeline are matched by their
 * Unix second. Children run side by side, so the merged duration is the longest child's.
 */
public final class ResultMerger {

    private ResultMerger() {
    }

    /**
     * @param parts results of each engine instance
     * @return the combined results
     */
    public static RunResults merge(List<RunResults> parts) {
        Map<String, CaseMeasurement> cases = new LinkedHashMap<>();
        Map<String, FailureCount> failures = new LinkedHashMap<>();
        Map<Long, TimelineSample> seconds = new TreeMap<>();
        double elapsed = 0;
        for (RunResults part : parts) {
            part.cases().forEach(c -> cases.merge(c.name(), c, CaseMeasurement::plus));
            part.failures().forEach(f -> failures.merge(f.key(), f, (a, b) -> a.plus(b.occurrences())));
            part.timeline().forEach(s -> seconds.merge(s.epochSecond(), s, TimelineSample::plus));
            elapsed = Math.max(elapsed, part.elapsedSeconds());
        }
        return new RunResults(new ArrayList<>(cases.values()), new ArrayList<>(failures.values()),
                new ArrayList<>(seconds.values()), elapsed);
    }
}
