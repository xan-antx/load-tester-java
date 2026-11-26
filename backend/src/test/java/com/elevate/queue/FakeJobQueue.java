package com.elevate.queue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An in-memory job queue for tests: no AWS involved. */
public class FakeJobQueue implements JobQueue {

    private final Deque<ReceivedJob> waiting = new ArrayDeque<>();
    private final List<JobMessage> sent = new CopyOnWriteArrayList<>();
    private final List<String> deleted = new CopyOnWriteArrayList<>();

    @Override
    public synchronized void send(JobMessage message) {
        sent.add(message);
        waiting.add(new ReceivedJob("receipt-" + sent.size(), message));
    }

    @Override
    public synchronized List<ReceivedJob> receive(int maxMessages, Duration wait) {
        List<ReceivedJob> out = new ArrayList<>();
        while (out.size() < maxMessages && !waiting.isEmpty()) {
            out.add(waiting.poll());
        }
        return out;
    }

    @Override
    public void delete(String receiptHandle) {
        deleted.add(receiptHandle);
    }

    /** Every message sent so far. */
    public List<JobMessage> sent() {
        return sent;
    }

    /** Every receipt handle deleted so far. */
    public List<String> deleted() {
        return deleted;
    }
}
