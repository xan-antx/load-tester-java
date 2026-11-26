package com.elevate.queue;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.service.JobRunner;

/**
 * The queue-mode worker (Java counterpart of the Python {@code worker.py}): long-polls the
 * queue, runs each job on a load engine and stores its results in the shared database, then
 * deletes the message — also when the job failed, so a bad job is not retried forever.
 *
 * <p>Started with {@code java -jar load-tester.jar worker} (see {@link WorkerMode}).
 */
public class QueueWorker {

    private static final Logger LOG = LoggerFactory.getLogger(QueueWorker.class);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final JobQueue queue;
    private final JobRunner jobs;
    private volatile boolean running = true;

    /**
     * @param queue the job queue
     * @param jobs  runs a job and stores the outcome
     */
    public QueueWorker(JobQueue queue, JobRunner jobs) {
        this.queue = queue;
        this.jobs = jobs;
    }

    /** Polls until {@link #stop()} is called or the thread is interrupted. */
    public void runUntilStopped() {
        LOG.info("Worker started. Polling the queue for jobs...");
        while (running && !Thread.currentThread().isInterrupted()) {
            processNext();
        }
    }

    /** Asks the loop to end after the current poll. */
    public void stop() {
        running = false;
    }

    /**
     * Receives at most one job and runs it.
     *
     * @return how many jobs were processed (0 or 1)
     */
    public int processNext() {
        List<JobQueue.ReceivedJob> received = queue.receive(1, WAIT);
        for (JobQueue.ReceivedJob job : received) {
            JobMessage message = job.message();
            LOG.info("Picked up job {} (type: {})", message.jobId(), message.jobType());
            try {
                if ("api".equals(message.jobType()) || "website".equals(message.jobType())) {
                    jobs.run(message.toPlan(), RequestHeaders.NONE);
                } else {
                    LOG.warn("Unknown job_type: {}, skipping", message.jobType());
                }
            } catch (RuntimeException e) {
                LOG.error("Error processing job {}", message.jobId(), e);
            } finally {
                queue.delete(job.receiptHandle());
                LOG.info("Job {} processed and removed from the queue", message.jobId());
            }
        }
        return received.size();
    }
}
