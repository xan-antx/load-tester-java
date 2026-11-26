package com.elevate.queue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The job queue on AWS SQS (AWS SDK v2), the Java counterpart of the Python
 * {@code sqs_client.py}: JSON message bodies with snake_case keys, long polling, and a
 * delete once a job has been processed.
 */
public class SqsJobQueue implements JobQueue {

    private static final Logger LOG = LoggerFactory.getLogger(SqsJobQueue.class);
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private final SqsClient sqs;
    private final String queueUrl;

    /**
     * @param sqs      the SQS client (region and credentials come from the environment)
     * @param queueUrl {@code SQS_QUEUE_URL}
     */
    public SqsJobQueue(SqsClient sqs, String queueUrl) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
    }

    @Override
    public void send(JobMessage message) {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(JSON.writeValueAsString(message))
                .build());
    }

    @Override
    public List<ReceivedJob> receive(int maxMessages, Duration wait) {
        List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                .maxNumberOfMessages(maxMessages).waitTimeSeconds((int) wait.toSeconds()).build()).messages();
        List<ReceivedJob> jobs = new ArrayList<>();
        for (Message m : messages) {
            try {
                jobs.add(new ReceivedJob(m.receiptHandle(), JSON.readValue(m.body(), JobMessage.class)));
            } catch (JacksonException e) {
                // Not a job: skip it, as the Python worker does.
                LOG.warn("Skipping a queue message that is not a valid job");
            }
        }
        return jobs;
    }

    @Override
    public void delete(String receiptHandle) {
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(receiptHandle).build());
    }
}
