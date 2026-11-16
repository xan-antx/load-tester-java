package com.elevate.engine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import com.elevate.analysis.outcome.Outcome;

/**
 * All measurements of one engine instance: a {@link CaseRecorder} per request template,
 * a counter per outcome (for capacity steps) and the number of active users.
 *
 * <p>The maps and lists are filled in the constructor and never change afterwards, so many
 * threads may read them without locking; only the counters inside them change, and those
 * are thread-safe themselves.
 */
final class RunRecorder {

    private final List<CaseRecorder> cases;
    private final Map<Outcome, LongAdder> outcomes;
    private final AtomicInteger activeUsers = new AtomicInteger();

    RunRecorder(List<RequestTemplate> templates) {
        this.cases = templates.stream().map(CaseRecorder::new).toList();
        Map<Outcome, LongAdder> counters = new EnumMap<>(Outcome.class);
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome, new LongAdder());
        }
        this.outcomes = Collections.unmodifiableMap(counters);
    }

    /**
     * Records one finished request of template {@code index}.
     *
     * @see CaseRecorder#record(int, long, long, String)
     */
    void record(int index, int status, long micros, long bytes, String errorText) {
        Outcome outcome = cases.get(index).record(status, micros, bytes, errorText);
        outcomes.get(outcome).increment();
    }

    /** The per-case recorders, in template order. */
    List<CaseRecorder> cases() {
        return cases;
    }

    /** Requests per outcome so far. */
    Map<Outcome, Long> outcomeTotals() {
        Map<Outcome, Long> totals = new EnumMap<>(Outcome.class);
        outcomes.forEach((outcome, counter) -> totals.put(outcome, counter.sum()));
        return totals;
    }

    /** The active-user counter (incremented and decremented by each virtual user). */
    AtomicInteger activeUsers() {
        return activeUsers;
    }
}
