package com.elevate.queue;

import java.util.Arrays;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.elevate.LoadTesterApplication;
import com.elevate.service.JobRunner;

/**
 * {@code java -jar load-tester.jar worker}: the same application without a web server, with
 * queue mode switched on, running a {@link QueueWorker} until the process is stopped.
 */
@Configuration
@Profile(WorkerMode.PROFILE)
public class WorkerMode {

    /** First command-line argument that selects worker mode. */
    public static final String COMMAND = "worker";
    /** Spring profile active in worker mode. */
    public static final String PROFILE = "worker";

    /** The worker loop. */
    @Bean
    public QueueWorker queueWorker(JobQueue jobQueue, JobRunner jobRunner) {
        return new QueueWorker(jobQueue, jobRunner);
    }

    /** Runs the loop once the context has started. */
    @Bean
    public ApplicationRunner workerLoop(QueueWorker worker) {
        return (ApplicationArguments args) -> worker.runUntilStopped();
    }

    /**
     * Starts the worker.
     *
     * @param args command-line arguments after {@code worker}
     */
    public static void start(String[] args) {
        SpringApplication app = new SpringApplication(LoadTesterApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles(PROFILE);
        // A command-line property outranks application.yml's ${USE_SQS:false}.
        String[] withQueue = Arrays.copyOf(args, args.length + 1);
        withQueue[args.length] = "--elevate.queue.use-sqs=true";
        app.run(withQueue);
    }
}
