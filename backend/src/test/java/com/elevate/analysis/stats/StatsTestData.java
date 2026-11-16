package com.elevate.analysis.stats;

import java.util.List;

import org.HdrHistogram.Histogram;

/** Builds measurements from known latencies for tests. */
public final class StatsTestData {

    private StatsTestData() {
    }

    /**
     * @param name     case name
     * @param failures failed requests
     * @param millis   one response time per request, in ms
     * @return the measurement
     */
    public static CaseMeasurement measurement(String name, long failures, long... millis) {
        Histogram h = Histograms.newHistogram();
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (long ms : millis) {
            long micros = ms * 1000;
            h.recordValue(micros);
            sum += micros;
            min = Math.min(min, micros);
            max = Math.max(max, micros);
        }
        return new CaseMeasurement(name, "POST", millis.length, failures, sum, min, max, 10L * millis.length, h);
    }

    /** Results of one run with these cases over {@code seconds}. */
    public static RunResults results(double seconds, CaseMeasurement... cases) {
        return new RunResults(List.of(cases), List.of(), List.of(), seconds);
    }

    /** {@code n} requests of {@code ms} each, {@code failures} of them failed. */
    public static CaseMeasurement repeated(String name, int n, long failures, long ms) {
        long[] values = new long[n];
        java.util.Arrays.fill(values, ms);
        return measurement(name, failures, values);
    }
}
