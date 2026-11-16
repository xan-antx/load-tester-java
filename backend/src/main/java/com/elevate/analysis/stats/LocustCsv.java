package com.elevate.analysis.stats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.HdrHistogram.Histogram;

/**
 * Writes results in the three CSV formats Locust produces ({@code --csv}), because the
 * React frontend parses exactly those: the stats table, the failures list and the
 * per-second history. Only the format comes from Locust; the numbers are our own.
 */
public final class LocustCsv {

    /** Header of the stats CSV. */
    public static final String STATS_HEADER = "Type,Name,Request Count,Failure Count,Median Response Time,"
            + "Average Response Time,Min Response Time,Max Response Time,Average Content Size,Requests/s,"
            + "Failures/s,50%,66%,75%,80%,90%,95%,98%,99%,99.9%,99.99%,100%";
    /** Header of the failures CSV. */
    public static final String FAILURES_HEADER = "Method,Name,Error,Occurrences";
    /** Header of the history CSV. */
    public static final String HISTORY_HEADER = "Timestamp,User Count,Type,Name,Requests/s,Failures/s,50%,95%,99%,"
            + "Total Request Count,Total Failure Count,Total Average Response Time";
    /** Seconds of history the per-second percentiles look back over. */
    public static final int PERCENTILE_WINDOW_SECONDS = 5;

    private static final String NOT_AVAILABLE = "N/A";

    private LocustCsv() {
    }

    /**
     * @param table the statistics, or {@code null}
     * @return the stats CSV, or {@code null} when there is no table
     */
    public static String stats(StatsTable table) {
        if (table == null) {
            return null;
        }
        StringBuilder csv = new StringBuilder(STATS_HEADER).append('\n');
        table.cases().forEach(row -> csv.append(statsRow(row)).append('\n'));
        csv.append(statsRow(table.aggregated())).append('\n');
        return csv.toString();
    }

    private static String statsRow(CaseStats s) {
        StringJoiner row = new StringJoiner(",");
        row.add(escape(s.method())).add(escape(s.name()))
                .add(Long.toString(s.requests())).add(Long.toString(s.failures()))
                .add(number(s.medianMs())).add(number(s.averageMs()))
                .add(number(s.minMs())).add(number(s.maxMs()))
                .add(number(s.averageContentSize()))
                .add(number(s.requestsPerSecond(), 4)).add(number(s.failuresPerSecond(), 4));
        s.percentiles().forEach(p -> row.add(number(p)));
        return row.toString();
    }

    /**
     * @param failures failure tallies
     * @return the failures CSV, or {@code null} when nothing failed (Locust writes no file then)
     */
    public static String failures(List<FailureCount> failures) {
        if (failures.isEmpty()) {
            return null;
        }
        return FAILURES_HEADER + '\n' + failures.stream()
                .map(f -> String.join(",", f.method(), escape(f.caseName()), quote(f.message()),
                        Long.toString(f.occurrences())))
                .collect(Collectors.joining("\n")) + '\n';
    }

    /**
     * One "Aggregated" row per second. Requests/s and Failures/s are that second's counts;
     * percentiles cover the last {@value #PERCENTILE_WINDOW_SECONDS} seconds (one second
     * holds too few requests for a stable p95); the Total columns are running totals.
     *
     * @param timeline per-second samples, oldest first
     * @return the history CSV, or {@code null} when there are no samples
     */
    public static String history(List<TimelineSample> timeline) {
        if (timeline.isEmpty()) {
            return null;
        }
        StringBuilder csv = new StringBuilder(HISTORY_HEADER).append('\n');
        Deque<Histogram> window = new ArrayDeque<>();
        long totalRequests = 0;
        long totalFailures = 0;
        long totalMicros = 0;
        for (TimelineSample s : timeline) {
            window.addLast(s.histogram());
            if (window.size() > PERCENTILE_WINDOW_SECONDS) {
                window.removeFirst();
            }
            Histogram recent = Histograms.newHistogram();
            window.forEach(recent::add);
            totalRequests += s.requests();
            totalFailures += s.failures();
            totalMicros += s.sumMicros();
            csv.append(s.epochSecond()).append(',').append(s.users()).append(",,").append(StatsTable.AGGREGATED)
                    .append(',').append(s.requests()).append(',').append(s.failures())
                    .append(',').append(number(Histograms.percentileMs(recent, 50)))
                    .append(',').append(number(Histograms.percentileMs(recent, 95)))
                    .append(',').append(number(Histograms.percentileMs(recent, 99)))
                    .append(',').append(totalRequests).append(',').append(totalFailures)
                    .append(',').append(totalRequests == 0 ? NOT_AVAILABLE : number(totalMicros / 1000.0 / totalRequests))
                    .append('\n');
        }
        return csv.toString();
    }

    /** Up to 2 decimals, no trailing zeros; {@code N/A} for {@code null}. */
    static String number(Double value) {
        return value == null ? NOT_AVAILABLE : number(value, 2);
    }

    private static String number(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /** RFC 4180 quoting, only when the value needs it. */
    private static String escape(String value) {
        return value.contains(",") || value.contains("\"") || value.contains("\n") ? quote(value) : value;
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"").replace("\n", " ") + '"';
    }
}
