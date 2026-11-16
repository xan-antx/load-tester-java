package com.elevate.analysis.outcome;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.elevate.analysis.stats.FailureCount;

/**
 * Turns failure tallies into a short list of failure <i>types</i>, grouped by kind and
 * status code (e.g. "Server error — 500"), instead of one line per case and message.
 */
public final class FailureSummarizer {

    private FailureSummarizer() {
    }

    /**
     * @param failures failure tallies of a run
     * @return groups, largest first; each group's cases most frequent first
     */
    public static List<FailureGroup> summarize(List<FailureCount> failures) {
        Map<String, Builder> groups = new LinkedHashMap<>();
        for (FailureCount f : failures) {
            String key = f.outcome().kind() + "/" + f.statusCode();
            groups.computeIfAbsent(key, k -> new Builder(f)).add(f);
        }
        return groups.values().stream()
                .map(Builder::build)
                .sorted(Comparator.comparingLong(FailureGroup::total).reversed())
                .toList();
    }

    /** Collects one group (mutable while summarizing, then turned into a record). */
    private static final class Builder {
        private final FailureCount first;
        private final Map<String, Long> cases = new LinkedHashMap<>();
        private long total;

        Builder(FailureCount first) {
            this.first = first;
        }

        void add(FailureCount f) {
            total += f.occurrences();
            cases.merge(f.caseName(), f.occurrences(), Long::sum);
        }

        FailureGroup build() {
            List<FailureGroup.CaseOccurrences> sorted = cases.entrySet().stream()
                    .map(e -> new FailureGroup.CaseOccurrences(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingLong(FailureGroup.CaseOccurrences::occurrences).reversed()
                            .thenComparing(FailureGroup.CaseOccurrences::caseName))
                    .toList();
            return new FailureGroup(first.statusCode(), first.reason(), Objects.requireNonNull(first.outcome().kind()),
                    ResponseClassifier.meaningOf(first.outcome(), first.statusCode()), total, sorted);
        }
    }
}
