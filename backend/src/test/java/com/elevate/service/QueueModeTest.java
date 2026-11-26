package com.elevate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.analysis.probe.SitemapReader;
import com.elevate.analysis.target.TargetGuard;
import com.elevate.config.AppProperties;
import com.elevate.dto.StartRunRequest;
import com.elevate.exception.ConflictException;
import com.elevate.model.Expectation;
import com.elevate.model.JobType;
import com.elevate.model.RunCase;
import com.elevate.model.RunStatus;
import com.elevate.queue.FakeJobQueue;
import com.elevate.queue.JobQueue;

import tools.jackson.databind.json.JsonMapper;

/** Queue mode on the API side: jobs go to the queue, the slot is held until the run ends, 409 refusals. */
class QueueModeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AppProperties PROPS = new AppProperties(true, 1, List.of(), true,
            new AppProperties.Engine(Duration.ofMillis(500), Duration.ofSeconds(2), Duration.ofSeconds(30)),
            new AppProperties.Queue(true, "https://sqs.example/queue", "eu-north-1"));

    /** An empty provider: local mode. */
    static ObjectProvider<JobQueue> noQueue() {
        return new StaticListableBeanFactory().getBeanProvider(JobQueue.class);
    }

    private static ObjectProvider<JobQueue> provider(JobQueue queue) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("jobQueue", queue);
        return beans.getBeanProvider(JobQueue.class);
    }

    @Test
    void jobsAreSentToTheQueueAndTheSlotIsHeldUntilTheRunEnds() {
        RunStore store = mock(RunStore.class);
        JobRunner jobs = mock(JobRunner.class);
        ActiveRunLimiter limiter = new ActiveRunLimiter(PROPS);
        FakeJobQueue queue = new FakeJobQueue();
        RunExecutor executor = new RunExecutor(store, new RunRegistry(), limiter, jobs, provider(queue));
        JobPlan plan = new JobPlan("j1", JobType.API, "http://h:1", "/api/login",
                List.of(RunCase.edgeCase("valid_sample", "valid_input", null, "d", "{\"u\":1}", Expectation.ACCEPT)),
                5, 1, 10, null, null);
        when(store.loadPlan("j1")).thenReturn(plan);
        when(store.statusOf("j1")).thenReturn(Optional.of(RunStatus.QUEUED), Optional.of(RunStatus.COMPLETED));

        limiter.acquire();
        executor.launch(new PreparedRun("j1", List.of()), RequestHeaders.NONE);

        verify(store, timeout(5000).atLeast(2)).statusOf("j1");
        assertThat(queue.sent()).singleElement().satisfies(m -> {
            assertThat(m.jobId()).isEqualTo("j1");
            assertThat(m.targetPath()).isEqualTo("/api/login");
            assertThat(m.cases().getFirst().payload().get("u").asInt()).isEqualTo(1);
        });
        verify(jobs, never()).run(any(), any());
        await().atMost(Duration.ofSeconds(5)).until(() -> limiter.activeRuns() == 0);
        assertThatThrownBy(() -> executor.cancel("j1")).isInstanceOf(ConflictException.class)
                .hasMessageStartingWith("Stopping a test is not available in queue (SQS) mode");
        executor.shutdown();
    }

    @Test
    void headersAndCapacityRunsAreRefusedInQueueMode() {
        RunExecutor executor = mock(RunExecutor.class);
        when(executor.isQueueMode()).thenReturn(true);
        TargetGuard guard = mock(TargetGuard.class);
        when(guard.check(any())).thenReturn(Optional.empty());
        LoadTestService service = new LoadTestService(guard, new ActiveRunLimiter(PROPS), mock(RunStore.class), executor,
                mock(SitemapReader.class), Clock.systemUTC());
        String cases = "[{\"label\":\"a\",\"payload\":{}}]";
        StartRunRequest withHeaders = new StartRunRequest("http://h/x", JSON.readTree(cases),
                JSON.getNodeFactory().booleanNode(true), 5, 1, 10, JSON.readTree("{\"Authorization\":\"Bearer s\"}"),
                1, null, null, null, null);
        assertThatThrownBy(() -> service.startApiTest(withHeaders)).isInstanceOf(ConflictException.class)
                .hasMessage("Request headers are not supported in queue (SQS) mode.");
        StartRunRequest capacity = new StartRunRequest("http://h/x", JSON.readTree(cases),
                JSON.getNodeFactory().booleanNode(true), 5, 20, 10, null, 1, JSON.readTree("{\"p95_ms\":500}"),
                "capacity", JSON.readTree("{\"start_users\":2,\"step_users\":2,\"step_duration_seconds\":5,\"max_users\":4}"),
                null);
        assertThatThrownBy(() -> service.startApiTest(capacity)).isInstanceOf(ConflictException.class)
                .hasMessage("Capacity runs are not available in queue (SQS) mode.");
    }

    @Test
    void staleRunsAreNotTouchedInQueueMode() {
        RunStore store = mock(RunStore.class);
        new StaleRunRecovery(store, PROPS).afterSingletonsInstantiated();
        verify(store, never()).markLeftoversInterrupted();
    }

    @Test
    void localModeHasNoQueue() {
        RunExecutor executor = new RunExecutor(mock(RunStore.class), new RunRegistry(), new ActiveRunLimiter(PROPS),
                mock(JobRunner.class), noQueue());
        assertThat(executor.isQueueMode()).isFalse();
        executor.shutdown();
    }
}
