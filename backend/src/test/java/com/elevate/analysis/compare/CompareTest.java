package com.elevate.analysis.compare;

import static com.elevate.analysis.stats.StatsTestData.repeated;
import static com.elevate.analysis.stats.StatsTestData.results;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.elevate.analysis.stats.StatsTable;

/** Case comparison across runs, regression detection against a baseline, series statistics. */
class CompareTest {

    private static StatsTable table(double seconds, com.elevate.analysis.stats.CaseMeasurement... cases) {
        return StatsTable.of(results(seconds, cases));
    }

    private final StatsTable baseline = table(10, repeated("login", 20, 0, 100), repeated("signup", 20, 2, 100),
            repeated("rare", 3, 0, 100));
    private final StatsTable current = table(10, repeated("login", 20, 6, 120), repeated("signup", 20, 3, 120),
            repeated("rare", 3, 3, 120), repeated("new_case", 10, 0, 100));

    @Test
    void changedCasesComeFirst() {
        CaseComparison c = CaseComparison.of(List.of(baseline, current));
        assertThat(c.rows().getFirst().caseName()).isEqualTo("login");
        assertThat(c.rows().getFirst().spread()).isEqualTo(30.0);
        assertThat(c.rows().getFirst().changed()).isTrue();
        CaseComparison.Row rare = c.rows().stream().filter(r -> r.caseName().equals("rare")).findFirst().orElseThrow();
        assertThat(rare.changed()).as("low samples are not trusted").isFalse();
        assertThat(rare.cells().getFirst().lowSample()).isTrue();
        CaseComparison.Row added = c.rows().stream().filter(r -> r.caseName().equals("new_case")).findFirst().orElseThrow();
        assertThat(added.cells().getFirst()).isNull();
    }

    @Test
    void runsWithoutResultsHaveEmptyColumns() {
        CaseComparison c = CaseComparison.of(Arrays.asList(baseline, null));
        assertThat(c.rows()).allSatisfy(r -> assertThat(r.cells().get(1)).isNull());
    }

    @Test
    void regressionsAreCasesThatGotWorseByTenPoints() {
        RegressionReport r = RegressionReport.detect(baseline, current);
        assertThat(r.regressions()).singleElement().satisfies(x -> {
            assertThat(x.caseName()).isEqualTo("login");
            assertThat(x.increase()).isEqualTo(30.0);
            assertThat(x.baselineRequests()).isEqualTo(20);
        });
        assertThat(r.p95ChangePct()).isCloseTo(20.0, org.assertj.core.api.Assertions.within(0.2));
        assertThat(r.threshold()).isEqualTo(10.0);
        assertThat(r.minRequests()).isEqualTo(5);
    }

    @Test
    void noBaselineStatsMeansNoComparison() {
        RegressionReport r = RegressionReport.detect(null, current);
        assertThat(r.regressions()).isEmpty();
        assertThat(r.p95ChangePct()).isNull();
    }

    @Test
    void seriesSummaryGivesMeanStdMinMax() {
        SeriesSummary s = SeriesSummary.of(List.of(new SeriesSummary.Repeat(baseline, 0.0),
                new SeriesSummary.Repeat(current, 10.0), new SeriesSummary.Repeat(null, null)));
        assertThat(s.repeats()).isEqualTo(3);
        assertThat(s.overall().problemRate().mean()).isEqualTo(5.0);
        assertThat(s.overall().problemRate().std()).isEqualTo(7.07);
        assertThat(s.overall().problemRate().values()).containsExactly(0.0, 10.0, null);
        assertThat(s.cases().getFirst().caseName()).isEqualTo("rare");
        assertThat(s.cases().getFirst().maxRate()).isEqualTo(100.0);
        SeriesSummary single = SeriesSummary.of(List.of(new SeriesSummary.Repeat(baseline, 1.0)));
        assertThat(single.overall().p95().std()).isNull();
        assertThat(SeriesSummary.of(List.of(new SeriesSummary.Repeat(null, null))).overall().p95().mean()).isNull();
    }
}
