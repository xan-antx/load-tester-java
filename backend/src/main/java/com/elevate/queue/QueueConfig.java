package com.elevate.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.elevate.config.AppProperties;

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Queue-mode beans, created only when {@code USE_SQS=true}. Credentials come from the usual
 * AWS environment (the {@code aws-sqs-credentials} secret in Kubernetes), as with boto3.
 */
@Configuration
@ConditionalOnProperty(name = "elevate.queue.use-sqs", havingValue = "true")
public class QueueConfig {

    /** The SQS client for {@code AWS_DEFAULT_REGION}. */
    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(AppProperties properties) {
        return SqsClient.builder()
                .region(Region.of(properties.queue().awsRegion()))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    /** The job queue at {@code SQS_QUEUE_URL}. */
    @Bean
    public JobQueue jobQueue(SqsClient sqsClient, AppProperties properties) {
        return new SqsJobQueue(sqsClient, properties.queue().sqsQueueUrl());
    }
}
