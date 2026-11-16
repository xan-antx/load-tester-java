package com.elevate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.elevate.analysis.capacity.StepRecord;
import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.analysis.slo.Slo;
import com.elevate.analysis.slo.SloThreshold;
import com.elevate.analysis.stats.RunResults;
import com.elevate.analysis.stats.StatsTable;
import com.elevate.config.AppProperties;
import com.elevate.dto.RunAnalysis;
import com.elevate.engine.EngineSpec;
import com.elevate.engine.LoadEngine;
import com.elevate.engine.LoadProfile;
import com.elevate.exception.NotFoundException;
import com.elevate.exception.TooManyActiveRunsException;
import com.elevate.model.CapacityPlan;
import com.elevate.model.Expectation;
import com.elevate.model.JobType;
import com.elevate.model.RunCase;
import com.elevate.model.RunStatus;

/** Limiter, registry, cancel logic, split plan, engine-spec factory and run analysis. */
class RunControlTest {

    private static final AppProperties PROPS = new AppProperties(true, 2, List.of(), true,
            new AppProperties.Engine(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(30)));

    @Test
    void limiterAllowsMaxActiveRunsThen429() {
        ActiveRunLimiter limiter = new ActiveRunLimiter(PROPS);
        limiter.acquire();
        limiter.acquire();
        assertThat(limiter.activeRuns()).isEqualTo(2);
        assertThatThrownBy(limiter::acquire).isInstanceOf(TooManyActiveRunsException.class)
                .hasMessageStartingWith("2 load tests are already running");
        limiter.release();
        limiter.acquire();
    }

    @Test
    void registryStopsAnEngineWhoseCancelCameFirst() {
        RunRegistry registry = new RunRegistry();
        LoadEngine engine = mock(LoadEngine.class);
        registry.requestCancel("a");
        registry.register("a", engine);
        verify(engine).stop();
        LoadEngine other = mock(LoadEngine.class);
        registry.register("b", other);
        registry.requestCancel("b");
        verify(other).stop();
        registry.stopAll();
        assertThat(registry.isShuttingDown()).isTrue();
        registry.unregister("a");
    }

    @Test
    void cancelDependsOnWhatTheIdNames() {
        RunStore store = mock(RunStore.class);
        RunRegistry registry = new RunRegistry();
        RunExecutor executor = new RunExecutor(store, registry, new ActiveRunLimiter(PROPS), new EngineSpecFactory(PROPS));
        when(store.kindOf(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> executor.cancel("x")).isInstanceOf(NotFoundException.class).hasMessage("run 'x' not found");

        when(store.kindOf("queued")).thenReturn(Optional.of(RunStore.Kind.JOB));
        when(store.statusOf("queued")).thenReturn(Optional.of(RunStatus.QUEUED));
        executor.cancel("queued");
        verify(store).changeJobStatus("queued", RunStatus.CANCELLED, null);
        assertThat(registry.isCancelRequested("queued")).isTrue();

        when(store.kindOf("done")).thenReturn(Optional.of(RunStore.Kind.JOB));
        when(store.statusOf("done")).thenReturn(Optional.of(RunStatus.COMPLETED));
        executor.cancel("done");
        assertThat(registry.isCancelRequested("done")).isFalse();

        when(store.kindOf("series")).thenReturn(Optional.of(RunStore.Kind.SERIES));
        when(store.seriesState("series")).thenReturn(new RunStore.RepeatSeriesState(RunStatus.RUNNING, List.of("r1", "r2")));
        when(store.kindOf("r2")).thenReturn(Optional.of(RunStore.Kind.JOB));
        when(store.statusOf("r2")).thenReturn(Optional.of(RunStatus.RUNNING));
        executor.cancel("series");
        assertThat(registry.isCancelRequested("series")).isTrue();
        assertThat(registry.isCancelRequested("r2")).isTrue();
        verify(store, never()).changeJobStatus("r2", RunStatus.CANCELLED, null);

        when(store.kindOf("group")).thenReturn(Optional.of(RunStore.Kind.GROUP));
        when(store.childIds("group")).thenReturn(List.of("c1"));
        when(store.statusOf("c1")).thenReturn(Optional.of(RunStatus.RUNNING));
        executor.cancel("group");
        assertThat(registry.isCancelRequested("c1")).isTrue();
        executor.shutdown();
    }

    @Test
    void splitPlanSharesUsersEvenly() {
        assertThat(SplitPlan.isSplit(50)).isFalse();
        assertThat(SplitPlan.split(51)).containsExactly(26, 25);
        assertThat(SplitPlan.split(120)).containsExactly(40, 40, 40);
        assertThat(SplitPlan.split(1000)).hasSize(10).allSatisfy(n -> assertThat(n).isEqualTo(100));
    }

    @Test
    void engineSpecFactoryBuildsApiWebsiteAndCapacitySpecs() {
        EngineSpecFactory factory = new EngineSpecFactory(PROPS);
        JobPlan api = new JobPlan("j", JobType.API, "http://h:1", "/api/login",
                List.of(RunCase.edgeCase("a", "c", null, "d", "{}", Expectation.REJECT)), 5, 2, 10, null, null);
        EngineSpec spec = factory.create(api, RequestHeaders.NONE);
        assertThat(spec.templates().getFirst().uri().toString()).isEqualTo("http://h:1/api/login");
        assertThat(spec.templates().getFirst().method()).isEqualTo("POST");
        assertThat(spec.profile()).isEqualTo(new LoadProfile.Fixed(5, 2, Duration.ofSeconds(10)));

        JobPlan site = new JobPlan("w", JobType.WEBSITE, "http://h:1", "/", List.of(RunCase.page("/about")), 5, 2, 10,
                null, null);
        assertThat(factory.create(site, RequestHeaders.NONE).templates().getFirst().uri().toString())
                .isEqualTo("http://h:1/about");

        Slo slo = new Slo(java.util.Map.of(SloThreshold.P95_MS, 100.0));
        JobPlan capacity = new JobPlan("c", JobType.API, "http://h:1", "/", api.cases(), 40, 20, 24,
                new CapacityPlan(20, 20, 12, 40, true), slo);
        EngineSpec steps = factory.create(capacity, RequestHeaders.NONE);
        assertThat(steps.profile()).isInstanceOf(LoadProfile.Steps.class);
        StepRecord slow = new StepRecord(0, 20, 20, 12, 10, 0, 0, 500.0, 0, 0, 0, 0, 0);
        assertThat(steps.stepJudge().shouldStop(slow)).isTrue();
    }

    @Test
    void verdictOnlyForCompletedFixedRuns() {
        RunResults results = new RunResults(List.of(com.elevate.analysis.stats.StatsTestData.repeated("a", 10, 0, 100)),
                List.of(), List.of(), 10);
        StatsTable stats = StatsTable.of(results);
        Slo slo = new Slo(java.util.Map.of(SloThreshold.P95_MS, 500.0));
        RunAnalysis done = RunAnalyzer.analyze(results, stats, RunStatus.COMPLETED, slo, true, null, List.of());
        assertThat(done.sloVerdict().verdict()).isEqualTo("PASS");
        assertThat(done.outcomeSummary().handledCorrectlyPct()).isEqualTo(100.0);
        assertThat(RunAnalyzer.analyze(results, stats, RunStatus.CANCELLED, slo, true, null, List.of()).sloVerdict()).isNull();
        RunAnalysis capacity = RunAnalyzer.analyze(results, stats, RunStatus.COMPLETED, slo, false,
                new CapacityPlan(10, 10, 5, 10, true), List.of(new StepRecord(0, 10, 10, 5, 10, 0, 1000, 100.0, 0, 0, 0, 0, 0)));
        assertThat(capacity.sloVerdict()).isNull();
        assertThat(capacity.capacity().maxSustainableUsers()).isEqualTo(10);
        assertThat(capacity.capacity().headline()).startsWith("No breaking point");
        RunAnalysis empty = RunAnalyzer.analyze(RunResults.empty(), null, RunStatus.COMPLETED, slo, false, null, List.of());
        assertThat(empty.sloVerdict().verdict()).isNull();
    }
}
