package com.elevate.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.elevate.StubServer;
import com.elevate.StubServer.Answer;
import com.elevate.analysis.outcome.Outcome;
import com.elevate.analysis.stats.CaseMeasurement;
import com.elevate.analysis.stats.FailureCount;
import com.elevate.model.Expectation;

class LoadEngineTest {

    private static final ThinkTime FAST = new ThinkTime(Duration.ofMillis(10), Duration.ofMillis(20));

    private StubServer stub;

    @BeforeEach
    void start() throws Exception {
        stub = new StubServer(Map.of(
                "/ok", m -> Answer.json(200, "{\"ok\":true}"),
                "/bad", m -> Answer.json(400, "{}"),
                "/crash", m -> Answer.json(500, "{}")));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private List<RequestTemplate> templates() {
        return List.of(
                RequestTemplate.post("valid", URI.create(stub.url("/ok")), "{}", Expectation.ACCEPT),
                RequestTemplate.post("invalid_rejected", URI.create(stub.url("/bad")), "{}", Expectation.REJECT),
                RequestTemplate.post("invalid_accepted", URI.create(stub.url("/ok")), "{}", Expectation.REJECT),
                RequestTemplate.post("crash", URI.create(stub.url("/crash")), "{}", Expectation.REJECT));
    }

    private EngineSpec.Builder spec(LoadProfile profile) {
        return EngineSpec.builder().templates(templates()).profile(profile).thinkTime(FAST)
                .requestTimeout(Duration.ofSeconds(2));
    }

    @Test
    void runsForTheDurationAndClassifiesEveryResponse() {
        LoadEngine engine = new LoadEngine(spec(new LoadProfile.Fixed(4, 20, Duration.ofSeconds(2))).build());
        EngineResult result = engine.run();

        assertThat(result.stopReason()).isEqualTo(StopReason.COMPLETED);
        assertThat(engine.isRunning()).isFalse();
        List<CaseMeasurement> cases = result.results().cases();
        assertThat(cases).extracting(CaseMeasurement::name)
                .containsExactly("valid", "invalid_rejected", "invalid_accepted", "crash");
        assertThat(cases).allSatisfy(c -> assertThat(c.requests()).isPositive());
        assertThat(cases).allSatisfy(c -> assertThat(c.histogram().getTotalCount()).isEqualTo(c.requests()));
        assertThat(cases.get(0).failures()).isZero();
        assertThat(cases.get(1).failures()).isZero();
        assertThat(cases.get(2).failures()).isEqualTo(cases.get(2).requests());
        assertThat(result.results().failures()).extracting(FailureCount::outcome)
                .containsExactlyInAnyOrder(Outcome.ACCEPTED_INVALID_INPUT, Outcome.SERVER_ERROR);
        assertThat(result.results().elapsedSeconds()).isBetween(1.9, 3.0);
        assertThat(result.results().timeline()).hasSizeBetween(2, 3);
        long timelineRequests = result.results().timeline().stream().mapToLong(s -> s.requests()).sum();
        assertThat(timelineRequests).isEqualTo(cases.stream().mapToLong(CaseMeasurement::requests).sum());
    }

    @Test
    void stopEndsTheRunQuicklyAndLeavesNoUsersRunning() throws Exception {
        LoadEngine engine = new LoadEngine(spec(new LoadProfile.Fixed(10, 20, Duration.ofSeconds(30))).build());
        CompletableFuture<EngineResult> running = CompletableFuture.supplyAsync(engine::run,
                Executors.newVirtualThreadPerTaskExecutor());
        Thread.sleep(800);
        long started = System.nanoTime();
        engine.stop();
        EngineResult result = running.get(10, TimeUnit.SECONDS);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(result.stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(result.results().cases().stream().mapToLong(CaseMeasurement::requests).sum()).isPositive();
        assertThat(result.results().timeline().getLast().users()).isLessThanOrEqualTo(10);
    }

    @Test
    void stopBeforeRunGivesAnEmptyCancelledResult() {
        LoadEngine engine = new LoadEngine(spec(new LoadProfile.Fixed(5, 5, Duration.ofSeconds(30))).build());
        engine.stop();
        EngineResult result = engine.run();
        assertThat(result.stopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(result.results().cases()).allSatisfy(c -> assertThat(c.requests()).isZero());
        assertThatThrownBy(engine::run).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void connectionFailuresAreRecorded() {
        EngineSpec dead = EngineSpec.builder().profile(new LoadProfile.Fixed(2, 10, Duration.ofSeconds(1)))
                .templates(List.of(RequestTemplate.get("/", URI.create("http://127.0.0.1:9/"))))
                .thinkTime(FAST).requestTimeout(Duration.ofSeconds(1)).build();
        EngineResult result = new LoadEngine(dead).run();
        assertThat(result.results().failures()).isNotEmpty()
                .allSatisfy(f -> assertThat(f.outcome()).isEqualTo(Outcome.CONNECTION_ERROR));
        assertThat(result.results().failures().getFirst().message()).startsWith("connection: no response (");
    }

    @Test
    void stepProfileRecordsStepsAndStopsEarly() {
        AtomicInteger judged = new AtomicInteger();
        EngineSpec steps = spec(new LoadProfile.Steps(List.of(2, 4, 6), Duration.ofSeconds(1), 20))
                .stepJudge(step -> judged.incrementAndGet() == 2).build();
        EngineResult result = new LoadEngine(steps).run();
        assertThat(result.stopReason()).isEqualTo(StopReason.EARLY_STOP);
        assertThat(result.results().elapsedSeconds()).as("stopped at the 2 s boundary").isLessThan(2.8);
        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().getFirst().plannedUsers()).isEqualTo(2);
        assertThat(result.steps().get(1).plannedUsers()).isEqualTo(4);
        assertThat(result.steps().getFirst().requests()).isPositive();
        assertThat(result.steps().getFirst().serverErrors()).isPositive();
    }

    @Test
    void recordingFromManyThreadsLosesNothing() throws Exception {
        RunRecorder recorder = new RunRecorder(templates());
        int threads = 16;
        int perThread = 5_000;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int t = 0; t < threads; t++) {
                executor.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        recorder.record(2, 200, 1000 + i, 10, null);
                    }
                });
            }
        }
        CaseRecorder c = recorder.cases().get(2);
        Histogram drained = c.drainInterval();
        CaseMeasurement m = c.measurement(drained);
        assertThat(m.requests()).isEqualTo((long) threads * perThread);
        assertThat(drained.getTotalCount()).isEqualTo((long) threads * perThread);
        assertThat(m.failures()).isEqualTo((long) threads * perThread);
        assertThat(c.failureCounts()).singleElement().satisfies(f -> assertThat(f.occurrences()).isEqualTo((long) threads * perThread));
        assertThat(recorder.outcomeTotals()).containsEntry(Outcome.ACCEPTED_INVALID_INPUT, (long) threads * perThread);
        assertThat(m.minMicros()).isEqualTo(1000);
        assertThat(m.maxMicros()).isEqualTo(1000 + perThread - 1);
    }

    @Test
    void profilesAndThinkTime() {
        LoadProfile.Steps steps = new LoadProfile.Steps(List.of(10, 20), Duration.ofSeconds(5), 4);
        assertThat(steps.targetUsers(Duration.ofSeconds(4))).isEqualTo(10);
        assertThat(steps.targetUsers(Duration.ofSeconds(5))).isEqualTo(20);
        assertThat(steps.targetUsers(Duration.ofSeconds(60))).isEqualTo(20);
        assertThat(steps.totalDuration()).isEqualTo(Duration.ofSeconds(10));
        LoadProfile fixed = new LoadProfile.Fixed(7, 2, Duration.ofSeconds(3));
        assertThat(fixed.targetUsers(Duration.ZERO)).isEqualTo(7);
        Duration pause = ThinkTime.LOCUST_DEFAULT.next(new java.util.Random(1));
        assertThat(pause).isBetween(Duration.ofMillis(500), Duration.ofSeconds(2));
        assertThat(new ThinkTime(Duration.ofMillis(5), Duration.ofMillis(5)).next(new java.util.Random()))
                .isEqualTo(Duration.ofMillis(5));
        assertThatThrownBy(() -> new ThinkTime(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EngineSpec.builder().profile(fixed).build()).isInstanceOf(IllegalArgumentException.class);
    }
}
