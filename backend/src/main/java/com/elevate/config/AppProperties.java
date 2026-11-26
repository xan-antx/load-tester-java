package com.elevate.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed settings from {@code application.yml} ({@code elevate.*}), bound once at startup.
 *
 * @param allowPrivateTargets whether private, loopback, link-local and multicast targets are allowed
 * @param maxActiveRuns       load tests that may be in progress at once (at least 1)
 * @param corsAllowedOrigins  browser origins allowed to call the API
 * @param recoverStaleRuns    mark leftover queued/running runs as interrupted at startup
 * @param engine              load-engine settings
 * @param queue               queue mode (SQS) settings
 */
@ConfigurationProperties("elevate")
public record AppProperties(
        @DefaultValue("true") boolean allowPrivateTargets,
        @DefaultValue("1") int maxActiveRuns,
        @DefaultValue({"http://localhost:5173", "http://127.0.0.1:5173"}) List<String> corsAllowedOrigins,
        @DefaultValue("true") boolean recoverStaleRuns,
        @DefaultValue Engine engine,
        @DefaultValue Queue queue) {

    /** At least one run must be allowed. */
    public AppProperties {
        maxActiveRuns = Math.max(1, maxActiveRuns);
    }

    /**
     * @param thinkTimeMin   shortest pause between a virtual user's requests
     * @param thinkTimeMax   longest pause
     * @param requestTimeout time after which a request counts as "no response"
     */
    public record Engine(
            @DefaultValue("500ms") Duration thinkTimeMin,
            @DefaultValue("2s") Duration thinkTimeMax,
            @DefaultValue("30s") Duration requestTimeout) {
    }

    /**
     * Queue mode: start requests put jobs on an SQS queue and a separate worker runs them.
     * Off by default; the same environment variables as the Python version.
     *
     * @param useSqs      {@code USE_SQS}
     * @param sqsQueueUrl {@code SQS_QUEUE_URL}
     * @param awsRegion   {@code AWS_DEFAULT_REGION}
     */
    public record Queue(
            @DefaultValue("false") boolean useSqs,
            @DefaultValue("") String sqsQueueUrl,
            @DefaultValue("eu-north-1") String awsRegion) {
    }
}
