package com.elevate.engine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.HdrHistogram.Histogram;

import com.elevate.analysis.capacity.StepRecord;
import com.elevate.analysis.outcome.Outcome;
import com.elevate.analysis.stats.Histograms;

/**
 * Cuts a capacity run into its steps. The sampler feeds it every interval; an interval
 * belongs to the step its midpoint falls in. As soon as an interval reaches the end of its
 * step, the step is recorded and handed to the {@link EngineSpec.StepJudge}, so an early
 * stop happens right at the step boundary. (The last step is not judged: the run ends there.)
 *
 * <p>Used only by the sampler thread (and, at the very end, by the engine thread after the
 * sampler has stopped), so it needs no synchronisation.
 */
final class StepTracker {

    private final LoadProfile.Steps plan;
    private final EngineSpec.StepJudge judge;
    private final Runnable earlyStop;
    private final List<StepRecord> records = new ArrayList<>();
    private Open open;

    /**
     * @param plan      the step plan
     * @param judge     decides whether a finished step ends the run
     * @param earlyStop called when it does
     */
    StepTracker(LoadProfile.Steps plan, EngineSpec.StepJudge judge, Runnable earlyStop) {
        this.plan = plan;
        this.judge = judge;
        this.earlyStop = earlyStop;
    }

    /**
     * Adds one interval.
     *
     * @param startSeconds  interval start, seconds after the run started
     * @param endSeconds    interval end
     * @param interval      latencies of the interval
     * @param failures      failed requests in the interval
     * @param sumMicros     sum of response times in the interval
     * @param users         active users at the end of the interval
     * @param outcomeDeltas requests per outcome in the interval
     */
    void add(double startSeconds, double endSeconds, Histogram interval, long failures, long sumMicros, int users,
             Map<Outcome, Long> outcomeDeltas) {
        double middle = (startSeconds + endSeconds) / 2;
        int index = Math.min((int) (middle * 1000 / plan.stepDuration().toMillis()), plan.users().size() - 1);
        if (open != null && open.index != index) {
            close();
        }
        if (open == null) {
            open = new Open(index);
        }
        open.add(endSeconds - startSeconds, interval, failures, sumMicros, users, outcomeDeltas);
        long stepEndMillis = (open.index + 1L) * plan.stepDuration().toMillis();
        boolean lastStep = open.index == plan.users().size() - 1;
        if (!lastStep && endSeconds * 1000 >= stepEndMillis - BOUNDARY_TOLERANCE_MILLIS) {
            close();
        }
    }

    /** Whether a step is being measured (false right after a step was closed). */
    boolean hasOpenStep() {
        return open != null;
    }

    /** Records the step in progress, if any (called when the run ends). */
    void finish() {
        if (open != null) {
            records.add(open.toRecord(plan.users().get(open.index)));
            open = null;
        }
    }

    /** The recorded steps. */
    List<StepRecord> records() {
        return List.copyOf(records);
    }

    private void close() {
        StepRecord record = open.toRecord(plan.users().get(open.index));
        records.add(record);
        open = null;
        if (judge.shouldStop(record)) {
            earlyStop.run();
        }
    }

    /** The step being measured. */
    /** Samples arrive about once a second; one ending this close to a boundary closes the step. */
    private static final long BOUNDARY_TOLERANCE_MILLIS = 250;

    private static final class Open {
        private final int index;
        private final Histogram latencies = Histograms.newHistogram();
        private final Map<Outcome, Long> outcomes = new EnumMap<>(Outcome.class);
        private double seconds;
        private long failures;
        private long sumMicros;
        private int maxUsers;

        Open(int index) {
            this.index = index;
        }

        void add(double length, Histogram interval, long intervalFailures, long intervalMicros, int users,
                 Map<Outcome, Long> deltas) {
            seconds += length;
            latencies.add(interval);
            failures += intervalFailures;
            sumMicros += intervalMicros;
            maxUsers = Math.max(maxUsers, users);
            deltas.forEach((outcome, n) -> outcomes.merge(outcome, n, Long::sum));
        }

        StepRecord toRecord(int plannedUsers) {
            return new StepRecord(index, plannedUsers, maxUsers, (int) Math.round(seconds), latencies.getTotalCount(),
                    failures, sumMicros, Histograms.percentileMs(latencies, 95), count(Outcome.SERVER_ERROR),
                    count(Outcome.CONNECTION_ERROR), count(Outcome.ACCEPTED_INVALID_INPUT),
                    count(Outcome.REJECTED_VALID_INPUT), count(Outcome.RATE_LIMITED));
        }

        private long count(Outcome outcome) {
            return outcomes.getOrDefault(outcome, 0L);
        }
    }
}
