package com.elevate.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.model.Expectation;
import com.elevate.model.JobType;
import com.elevate.model.RunCase;
import com.elevate.service.JobPlan;
import com.elevate.service.JobRunner;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Queue messages, the SQS adapter (with a mocked SqsClient, no AWS) and the worker loop. */
class QueueTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUEUE_URL = "https://sqs.eu-north-1.amazonaws.com/1/jobs";

    private static JobPlan apiPlan() {
        return new JobPlan("job-1", JobType.API, "http://127.0.0.1:8000", "/api/login",
                List.of(RunCase.edgeCase("null_username", "null_value", null, "'username' set to null",
                        "{\"username\":null}", Expectation.REJECT)), 10, 5, 30, null, null);
    }

    @Test
    void messageUsesThePythonPayloadKeysAndRoundTrips() {
        SqsClient sqs = mock(SqsClient.class);
        new SqsJobQueue(sqs, QUEUE_URL).send(JobMessage.of(apiPlan()));
        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(sent.capture());
        assertThat(sent.getValue().queueUrl()).isEqualTo(QUEUE_URL);
        JsonNode body = JSON.readTree(sent.getValue().messageBody());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("job_id", "job_type", "target_url", "users",
                "spawn_rate", "duration_seconds", "target_path", "cases");
        assertThat(body.at("/cases/0/expect").asString()).isEqualTo("reject");
        assertThat(body.at("/cases/0/payload/username").isNull()).isTrue();

        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().messages(
                Message.builder().receiptHandle("r1").body(sent.getValue().messageBody()).build(),
                Message.builder().receiptHandle("r2").body("not json").build()).build());
        List<JobQueue.ReceivedJob> received = new SqsJobQueue(sqs, QUEUE_URL).receive(1, Duration.ofSeconds(10));
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.receiptHandle()).isEqualTo("r1");
            JobPlan plan = r.message().toPlan();
            assertThat(plan.jobId()).isEqualTo("job-1");
            assertThat(plan.cases().getFirst().getExpect()).isEqualTo(Expectation.REJECT);
            assertThat(plan.cases().getFirst().getPayloadJson()).isEqualTo("{\"username\":null}");
        });
        ArgumentCaptor<ReceiveMessageRequest> poll = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs).receiveMessage(poll.capture());
        assertThat(poll.getValue().waitTimeSeconds()).isEqualTo(10);

        new SqsJobQueue(sqs, QUEUE_URL).delete("r1");
        verify(sqs).deleteMessage(DeleteMessageRequest.builder().queueUrl(QUEUE_URL).receiptHandle("r1").build());
    }

    @Test
    void websiteMessagesCarryPaths() {
        JobPlan site = new JobPlan("w", JobType.WEBSITE, "http://site", null, List.of(RunCase.page("/"), RunCase.page("/about")),
                3, 1, 10, null, null);
        JobMessage m = JobMessage.of(site);
        assertThat(m.paths()).containsExactly("/", "/about");
        assertThat(m.cases()).isNull();
        assertThat(m.toPlan().jobType()).isEqualTo(JobType.WEBSITE);
        assertThat(m.toPlan().cases()).extracting(RunCase::getLabel).containsExactly("/", "/about");
    }

    @Test
    void workerRunsEachJobAndAlwaysDeletesTheMessage() {
        FakeJobQueue queue = new FakeJobQueue();
        JobRunner jobs = mock(JobRunner.class);
        QueueWorker worker = new QueueWorker(queue, jobs);
        assertThat(worker.processNext()).isZero();

        queue.send(JobMessage.of(apiPlan()));
        assertThat(worker.processNext()).isEqualTo(1);
        ArgumentCaptor<JobPlan> plan = ArgumentCaptor.forClass(JobPlan.class);
        verify(jobs).run(plan.capture(), eq(RequestHeaders.NONE));
        assertThat(plan.getValue().targetPath()).isEqualTo("/api/login");

        doThrow(new IllegalStateException("boom")).when(jobs).run(any(), any());
        queue.send(JobMessage.of(apiPlan()));
        assertThat(worker.processNext()).isEqualTo(1);
        queue.send(new JobMessage("x", "graphql", "http://h", 1, 1, 1, null, null, null));
        worker.processNext();
        assertThat(queue.deleted()).containsExactly("receipt-1", "receipt-2", "receipt-3");
    }

    @Test
    void stopEndsTheLoop() {
        FakeJobQueue queue = new FakeJobQueue();
        JobRunner jobs = mock(JobRunner.class);
        QueueWorker worker = new QueueWorker(queue, jobs);
        worker.stop();
        worker.runUntilStopped();
        verify(jobs, never()).run(any(), any());
    }
}
