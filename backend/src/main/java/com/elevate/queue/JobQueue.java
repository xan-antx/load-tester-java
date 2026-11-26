package com.elevate.queue;

import java.time.Duration;
import java.util.List;

/**
 * A queue of load-test jobs (queue mode). The API sends jobs; a worker receives, runs and
 * deletes them. {@link SqsJobQueue} is the real one; tests use an in-memory fake.
 */
public interface JobQueue {

    /** Puts a job on the queue. */
    void send(JobMessage message);

    /**
     * Long-polls for jobs. Messages whose body is not a valid job are skipped.
     *
     * @param maxMessages most messages to return
     * @param wait        how long to wait for one
     * @return the received jobs (possibly none)
     */
    List<ReceivedJob> receive(int maxMessages, Duration wait);

    /** Removes a processed message. */
    void delete(String receiptHandle);

    /**
     * @param receiptHandle handle needed to delete the message
     * @param message       the job
     */
    record ReceivedJob(String receiptHandle, JobMessage message) {
    }
}
