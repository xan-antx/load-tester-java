package com.elevate.analysis.stats;

import static com.elevate.analysis.stats.StatsTestData.measurement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import com.elevate.analysis.outcome.Outcome;

/** Histograms, exact merging, the statistics table and the Locust CSV formats. */
class StatsTest {

    @Test
    void histogramsSurviveTheDatabaseRoundTrip() {
        Histogram h = Histograms.newHistogram();
        LongStream.rangeClosed(1, 1000).forEach(v -> h.recordValue(v * 1000));
        Histogram back = Histograms.decode(Histograms.encode(h));
        assertThat(back.getTotalCount()).isEqualTo(1000);
        assertThat(back.getValueAtPercentile(95)).isEqualTo(h.getValueAtPercentile(95));
        assertThat(Histograms.decode(null).getTotalCount()).isZero();
        assertThat(Histograms.percentileMs(Histograms.newHistogram(), 95)).isNull();
        assertThat(Histograms.clamp(-5)).isZero();
        assertThat(Histograms.clamp(Long.MAX_VALUE)).isEqualTo(Histograms.HIGHEST_TRACKABLE_MICROS);
    }

    /**
     * The point of HdrHistogram: merging the histograms of parallel engine instances gives
     * exactly the percentiles of one histogram holding every value. Averaging each part's
     * p95 (what the Python version did) does not.
     */
    @Test
    void mergedPercentilesAreExact() {
        Random random = new Random(42);
        long[] fast = random.longs(3000, 50, 150).toArray();
        long[] slow = random.longs(1000, 400, 900).toArray();
        CaseMeasurement a = measurement("login", 0, fast);
        CaseMeasurement b = measurement("login", 5, slow);
        CaseMeasurement all = measurement("login", 5, LongStream.concat(LongStream.of(fast), LongStream.of(slow)).toArray());

        RunResults merged = ResultMerger.merge(List.of(StatsTestData.results(10, a), StatsTestData.results(10, b)));
        CaseMeasurement m = merged.cases().getFirst();

        assertThat(m.requests()).isEqualTo(4000);
        assertThat(m.failures()).isEqualTo(5);
        for (double p : CaseStats.PERCENTILES) {
            assertThat(m.histogram().getValueAtPercentile(p)).as("p%s", p).isEqualTo(all.histogram().getValueAtPercentile(p));
        }
        double averagedP95 = (a.histogram().getValueAtPercentile(95) * 3000.0 + b.histogram().getValueAtPercentile(95) * 1000.0) / 4000;
        assertThat(averagedP95).as("the request-weighted average is wrong").isNotCloseTo(
                m.histogram().getValueAtPercentile(95), within(10_000.0));
        assertThat(m.minMicros()).isEqualTo(Math.min(a.minMicros(), b.minMicros()));
        assertThat(m.maxMicros()).isEqualTo(Math.max(a.maxMicros(), b.maxMicros()));
        assertThat(a.histogram().getTotalCount()).as("inputs are not changed").isEqualTo(3000);
    }

    @Test
    void mergeAddsFailuresAndAlignsTimelineSeconds() {
        FailureCount f = new FailureCount("c", "POST", Outcome.SERVER_ERROR, 500, "r", "m", 3);
        Histogram h = Histograms.newHistogram();
        h.recordValue(1000);
        RunResults one = new RunResults(List.of(), List.of(f), List.of(new TimelineSample(100, 10, 1, 0, 1000, h)), 9);
        RunResults two = new RunResults(List.of(), List.of(f), List.of(new TimelineSample(100, 20, 1, 1, 1000, h),
                new TimelineSample(101, 20, 1, 0, 1000, h)), 10);
        RunResults merged = ResultMerger.merge(List.of(one, two));
        assertThat(merged.failures()).singleElement().satisfies(x -> assertThat(x.occurrences()).isEqualTo(6));
        assertThat(merged.timeline()).hasSize(2);
        assertThat(merged.timeline().getFirst().users()).isEqualTo(30);
        assertThat(merged.timeline().getFirst().histogram().getTotalCount()).isEqualTo(2);
        assertThat(merged.elapsedSeconds()).isEqualTo(10);
    }

    @Test
    void statsTableHasRowsAndAnAggregatedTotal() {
        StatsTable table = StatsTable.of(StatsTestData.results(10, measurement("a", 1, 100, 200), measurement("b", 0, 300)));
        assertThat(table.cases()).extracting(CaseStats::name).containsExactly("a", "b");
        CaseStats total = table.aggregated();
        assertThat(total.name()).isEqualTo("Aggregated");
        assertThat(total.requests()).isEqualTo(3);
        assertThat(total.averageMs()).isEqualTo(200.0);
        assertThat(total.requestsPerSecond()).isEqualTo(0.3);
        assertThat(total.minMs()).isEqualTo(100.0);
        assertThat(total.maxMs()).isEqualTo(300.0);
        assertThat(total.p95Ms()).isCloseTo(300.0, within(0.5));
        assertThat(table.cases().getFirst().failureRatePct()).isEqualTo(50.0);
        assertThat(StatsTable.of(RunResults.empty())).isNull();
    }

    @Test
    void statsCsvIsLocustsFormat() {
        String csv = LocustCsv.stats(StatsTable.of(StatsTestData.results(10, measurement("valid_sample", 0, 100, 100))));
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo(LocustCsv.STATS_HEADER);
        String[] row = lines[1].split(",");
        assertThat(row).hasSize(22);
        assertThat(List.of(row).subList(0, 4)).containsExactly("POST", "valid_sample", "2", "0");
        // Average, min and max are exact sums; percentiles are within HdrHistogram's 0.1%.
        assertThat(List.of(row).subList(5, 11)).containsExactly("100", "100", "100", "10", "0.2", "0");
        assertThat(Double.parseDouble(row[4])).isCloseTo(100.0, within(0.1));
        assertThat(Double.parseDouble(row[16])).isCloseTo(100.0, within(0.1));
        assertThat(lines[2]).startsWith(",Aggregated,2,0,");
        assertThat(LocustCsv.stats(null)).isNull();
    }

    @Test
    void percentilesNeverReadAboveTheSlowestRequest() {
        CaseStats row = CaseStats.of(measurement("x", 0, 152, 152, 150), 1);
        assertThat(row.maxMs()).isEqualTo(152.0);
        assertThat(row.percentiles()).allSatisfy(p -> assertThat(p).isBetween(row.minMs(), row.maxMs()));
        assertThat(row.p95Ms()).isEqualTo(152.0);
    }

    @Test
    void emptyCasesWriteNotAvailable() {
        CaseMeasurement none = CaseMeasurement.empty("never_sent", "POST");
        String csv = LocustCsv.stats(StatsTable.of(StatsTestData.results(10, none, measurement("x", 0, 5))));
        assertThat(csv.split("\n")[1]).contains("N/A");
    }

    @Test
    void failuresCsvQuotesTheMessage() {
        FailureCount f = new FailureCount("very_long_string_username", "POST", Outcome.ACCEPTED_INVALID_INPUT, 200,
                "expected a 4xx rejection, got 200", "accepted_invalid_input (200): expected a 4xx rejection, got 200", 11);
        assertThat(LocustCsv.failures(List.of(f))).isEqualTo(LocustCsv.FAILURES_HEADER + "\n"
                + "POST,very_long_string_username,\"accepted_invalid_input (200): expected a 4xx rejection, got 200\",11\n");
        assertThat(LocustCsv.failures(List.of())).isNull();
    }

    @Test
    void historyCsvHasRunningTotalsAndWindowedPercentiles() {
        Histogram h = Histograms.newHistogram();
        h.recordValue(100_000);
        List<TimelineSample> timeline = List.of(new TimelineSample(1000, 5, 1, 0, 100_000, h),
                new TimelineSample(1001, 10, 1, 1, 100_000, h));
        String[] lines = LocustCsv.history(timeline).split("\n");
        assertThat(lines[0]).isEqualTo(LocustCsv.HISTORY_HEADER);
        String[] first = lines[1].split(",");
        assertThat(List.of(first).subList(0, 6)).containsExactly("1000", "5", "", "Aggregated", "1", "0");
        assertThat(Double.parseDouble(first[7])).as("p95 within 0.1%").isCloseTo(100.0, within(0.1));
        assertThat(List.of(first).subList(9, 12)).containsExactly("1", "0", "100");
        String[] second = lines[2].split(",");
        assertThat(List.of(second).subList(0, 6)).containsExactly("1001", "10", "", "Aggregated", "1", "1");
        assertThat(List.of(second).subList(9, 12)).containsExactly("2", "1", "100");
        assertThat(LocustCsv.history(List.of())).isNull();
    }

    @Test
    void numbersAreRoundedAndPlain() {
        assertThat(Numbers.round(2.345, 2)).isEqualTo(2.35);
        assertThat(Numbers.percent(1, 3L, 1)).isEqualTo(33.3);
        assertThat(Numbers.percent(1, null, 1)).isNull();
        assertThat(Numbers.plain(500.0)).isEqualTo("500");
        assertThat(Numbers.plain(20.0 / 3)).isEqualTo("6.66667");
        assertThat(LocustCsv.number(null)).isEqualTo("N/A");
    }
}
