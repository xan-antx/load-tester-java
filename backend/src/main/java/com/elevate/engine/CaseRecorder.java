package com.elevate.engine;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import com.elevate.analysis.outcome.Outcome;
import com.elevate.analysis.outcome.ResponseClassifier;
import com.elevate.analysis.stats.CaseMeasurement;
import com.elevate.analysis.stats.FailureCount;
import com.elevate.analysis.stats.Histograms;

/**
 * Collects the measurements of one case while many virtual users record into it at once.
 *
 * <p><b>Why an HdrHistogram {@link Recorder}:</b> virtual users (writers) call
 * {@link Recorder#recordValue} concurrently; it is wait-free, so recording never blocks a
 * user or makes the measurement slower. Once a second the sampler (the single reader) calls
 * {@link #drainInterval()}, which atomically swaps in a fresh histogram and hands back the
 * values of the last interval. The reader adds those to the case's cumulative histogram,
 * which only the reader ever touches. So there is no lock on the hot path, nothing is lost
 * or counted twice, and we get exact per-second <i>and</i> whole-run histograms. The
 * alternative — one histogram per thread, merged at the end — would not give per-second
 * values for the timeline, and with a thousand virtual users means a thousand histograms.
 *
 * <p>Counters are {@link LongAdder}s (cheap under contention), min/max are
 * {@link LongAccumulator}s and failure tallies live in a {@link ConcurrentHashMap}.
 */
final class CaseRecorder {

    private final RequestTemplate template;
    private final Recorder latencies = Histograms.newRecorder();
    private final LongAdder requests = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder sumMicros = new LongAdder();
    private final LongAdder contentBytes = new LongAdder();
    private final LongAccumulator minMicros = new LongAccumulator(Math::min, Long.MAX_VALUE);
    private final LongAccumulator maxMicros = new LongAccumulator(Math::max, 0);
    private final ConcurrentMap<String, Tally> failureTallies = new ConcurrentHashMap<>();

    CaseRecorder(RequestTemplate template) {
        this.template = template;
    }

    /**
     * Records one finished request. Called by many virtual-user threads at once.
     *
     * @param status       HTTP status, or {@link ResponseClassifier#NO_RESPONSE}
     * @param micros       response time
     * @param bytes        response body size
     * @param errorText    the connection error when there was no response
     * @return the request's outcome
     */
    Outcome record(int status, long micros, long bytes, String errorText) {
        Outcome outcome = ResponseClassifier.classify(status, template.expectation());
        requests.increment();
        sumMicros.add(micros);
        contentBytes.add(bytes);
        minMicros.accumulate(micros);
        maxMicros.accumulate(micros);
        if (outcome.isFailure()) {
            failures.increment();
            ResponseClassifier.FailureDetail detail = ResponseClassifier.describe(outcome, status,
                    template.expectation(), template.name(), errorText);
            Integer code = status == ResponseClassifier.NO_RESPONSE ? null : status;
            failureTallies.computeIfAbsent(detail.message(), m -> new Tally(outcome, code, detail.reason()))
                    .count.increment();
        }
        latencies.recordValue(Histograms.clamp(micros));
        return outcome;
    }

    /** The latencies recorded since the previous call. Only the sampler thread calls this. */
    Histogram drainInterval() {
        return latencies.getIntervalHistogram();
    }

    /** Failed requests so far. */
    long failures() {
        return failures.sum();
    }

    /** Sum of response times so far. */
    long sumMicros() {
        return sumMicros.sum();
    }

    /**
     * The whole-run measurement. Call only after every virtual user has stopped.
     *
     * @param cumulative the reader's histogram of every drained interval
     */
    CaseMeasurement measurement(Histogram cumulative) {
        return new CaseMeasurement(template.name(), template.method(), requests.sum(), failures.sum(), sumMicros.sum(),
                minMicros.get(), maxMicros.get(), contentBytes.sum(), cumulative);
    }

    /** The failure tallies. Call only after every virtual user has stopped. */
    List<FailureCount> failureCounts() {
        return failureTallies.entrySet().stream()
                .map(e -> new FailureCount(template.name(), template.method(), e.getValue().outcome,
                        e.getValue().statusCode, e.getValue().reason, e.getKey(), e.getValue().count.sum()))
                .toList();
    }

    /** One failure message and how often it happened. */
    private record Tally(Outcome outcome, Integer statusCode, String reason, LongAdder count) {
        Tally(Outcome outcome, Integer statusCode, String reason) {
            this(outcome, statusCode, reason, new LongAdder());
        }
    }
}
