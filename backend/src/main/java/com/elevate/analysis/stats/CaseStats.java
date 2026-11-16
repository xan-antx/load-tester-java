package com.elevate.analysis.stats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.HdrHistogram.Histogram;

/**
 * One row of the statistics table, in milliseconds: the numbers of Locust's stats CSV.
 *
 * @param name               case label, page path or {@code Aggregated}
 * @param method             HTTP method (empty for the Aggregated row)
 * @param requests           request count
 * @param failures           failure count
 * @param averageMs          exact average response time
 * @param minMs              fastest response
 * @param maxMs              slowest response
 * @param averageContentSize average body size in bytes
 * @param requestsPerSecond  throughput over the run
 * @param failuresPerSecond  failures per second over the run
 * @param percentiles        values at {@link #PERCENTILES}, or {@code null}s when there were no requests
 */
public record CaseStats(String name, String method, long requests, long failures, double averageMs,
                        double minMs, double maxMs, double averageContentSize, double requestsPerSecond,
                        double failuresPerSecond, List<Double> percentiles) {

    /** The percentile columns of Locust's CSV, in order. */
    public static final List<Double> PERCENTILES = List.of(50.0, 66.0, 75.0, 80.0, 90.0, 95.0, 98.0, 99.0,
            99.9, 99.99, 100.0);

    /**
     * Computes a row from a measurement.
     *
     * @param m              the measurement
     * @param elapsedSeconds run length, for the per-second rates
     * @return the row
     */
    public static CaseStats of(CaseMeasurement m, double elapsedSeconds) {
        Histogram h = m.histogram();
        List<Double> values = new ArrayList<>(PERCENTILES.size());
        PERCENTILES.forEach(p -> values.add(Histograms.percentileMs(h, p)));
        boolean any = m.requests() > 0;
        double seconds = elapsedSeconds > 0 ? elapsedSeconds : 1;
        return new CaseStats(m.name(), m.method(), m.requests(), m.failures(),
                any ? m.sumMicros() / 1000.0 / m.requests() : 0,
                any ? m.minMicros() / 1000.0 : 0,
                any ? m.maxMicros() / 1000.0 : 0,
                any ? (double) m.contentBytes() / m.requests() : 0,
                m.requests() / seconds, m.failures() / seconds,
                Collections.unmodifiableList(values));
    }

    /** The value at one of the {@link #PERCENTILES}, or {@code null} without data. */
    public Double percentile(double level) {
        int index = PERCENTILES.indexOf(level);
        if (index < 0) {
            throw new IllegalArgumentException("not a reported percentile: " + level);
        }
        return percentiles.get(index);
    }

    /** The median (p50). */
    public Double medianMs() {
        return percentile(50.0);
    }

    /** The 95th percentile. */
    public Double p95Ms() {
        return percentile(95.0);
    }

    /** Failure rate in percent, or {@code null} without requests. */
    public Double failureRatePct() {
        return requests == 0 ? null : failures * 100.0 / requests;
    }
}
