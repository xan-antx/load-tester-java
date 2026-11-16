package com.elevate.engine;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import org.HdrHistogram.Histogram;

import com.elevate.analysis.outcome.Outcome;
import com.elevate.analysis.stats.CaseMeasurement;
import com.elevate.analysis.stats.FailureCount;
import com.elevate.analysis.stats.Histograms;
import com.elevate.analysis.stats.RunResults;
import com.elevate.analysis.stats.TimelineSample;

/**
 * The single reader of the engine's recorders. Scheduled once a second, it drains every
 * case's interval histogram, adds it to that case's cumulative histogram, and writes one
 * {@link TimelineSample} (users, requests, failures and latencies of that second). For a
 * capacity run it also feeds the {@link StepTracker}.
 *
 * <p>Thread confinement: every field here is used by one thread at a time — the scheduler
 * thread while the run is going, then the engine thread for {@link #finish()} after the
 * scheduler has terminated (which guarantees it sees everything the scheduler wrote).
 */
final class IntervalSampler {

    /** A final partial interval shorter than this is folded into the previous second. */
    private static final double MIN_FINAL_INTERVAL_SECONDS = 0.5;

    private final RunRecorder recorder;
    private final Clock clock;
    private final DoubleSupplier elapsedSeconds;
    private final StepTracker steps;
    private final List<Histogram> cumulative;
    private final List<TimelineSample> timeline = new ArrayList<>();
    private Map<Outcome, Long> previousOutcomes;
    private long previousFailures;
    private long previousMicros;
    private double previousElapsed;

    /**
     * @param recorder       the recorders to drain
     * @param clock          wall clock for timestamps
     * @param elapsedSeconds seconds since the run started
     * @param steps          step tracker of a capacity run, or {@code null}
     */
    IntervalSampler(RunRecorder recorder, Clock clock, DoubleSupplier elapsedSeconds, StepTracker steps) {
        this.recorder = recorder;
        this.clock = clock;
        this.elapsedSeconds = elapsedSeconds;
        this.steps = steps;
        this.cumulative = recorder.cases().stream().map(c -> Histograms.newHistogram()).toList();
        this.previousOutcomes = new EnumMap<>(Outcome.class);
    }

    /** Takes one sample. Runs on the scheduler thread once a second. */
    void sample() {
        take(false);
    }

    /**
     * Takes the last sample and returns the results. Call once, after the scheduler has
     * stopped and every virtual user has finished.
     */
    RunResults finish() {
        take(true);
        if (steps != null) {
            steps.finish();
        }
        List<CaseRecorder> cases = recorder.cases();
        List<CaseMeasurement> measurements = new ArrayList<>();
        List<FailureCount> failures = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            measurements.add(cases.get(i).measurement(cumulative.get(i)));
            failures.addAll(cases.get(i).failureCounts());
        }
        return new RunResults(measurements, failures, timeline, previousElapsed);
    }

    private void take(boolean last) {
        double now = elapsedSeconds.getAsDouble();
        Histogram interval = Histograms.newHistogram();
        List<CaseRecorder> cases = recorder.cases();
        long failures = 0;
        long micros = 0;
        for (int i = 0; i < cases.size(); i++) {
            Histogram drained = cases.get(i).drainInterval();
            cumulative.get(i).add(drained);
            interval.add(drained);
            failures += cases.get(i).failures();
            micros += cases.get(i).sumMicros();
        }
        Map<Outcome, Long> outcomes = recorder.outcomeTotals();
        Map<Outcome, Long> deltas = new EnumMap<>(Outcome.class);
        outcomes.forEach((o, n) -> deltas.put(o, n - previousOutcomes.getOrDefault(o, 0L)));
        TimelineSample sample = new TimelineSample(clock.instant().getEpochSecond(), recorder.activeUsers().get(),
                interval.getTotalCount(), failures - previousFailures, micros - previousMicros, interval);
        boolean shortTail = last && now - previousElapsed < MIN_FINAL_INTERVAL_SECONDS;
        if (shortTail && !timeline.isEmpty()) {
            timeline.add(foldInto(timeline.removeLast(), sample));
        } else if (sample.requests() > 0 || !last) {
            timeline.add(sample);
        }
        // A short tail after a step was just closed is not a step of its own.
        if (steps != null && !(shortTail && !steps.hasOpenStep())) {
            steps.add(previousElapsed, now, interval, failures - previousFailures, micros - previousMicros,
                    recorder.activeUsers().get(), deltas);
        }
        previousOutcomes = outcomes;
        previousFailures = failures;
        previousMicros = micros;
        previousElapsed = now;
    }

    /** The previous second with a short final interval's requests added (its users and time kept). */
    private static TimelineSample foldInto(TimelineSample previous, TimelineSample tail) {
        Histogram merged = Histograms.copyOf(previous.histogram());
        merged.add(tail.histogram());
        return new TimelineSample(previous.epochSecond(), previous.users(), previous.requests() + tail.requests(),
                previous.failures() + tail.failures(), previous.sumMicros() + tail.sumMicros(), merged);
    }
}
