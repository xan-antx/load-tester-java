package com.elevate.engine;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

import com.elevate.analysis.outcome.ResponseClassifier;

/**
 * One simulated user: sends a request, records the answer, thinks, and repeats until the
 * engine stops it. Each user runs on its own <b>virtual thread</b>, so a thousand users
 * blocked in {@link HttpClient#send} cost a thousand small heap objects, not a thousand OS
 * threads.
 *
 * <p>Each user starts at a random case and then cycles through the cases in order. Across
 * users the starting points are spread evenly, and within one user no case repeats before
 * every other case has been sent, so every case gets a fair share of the requests even in
 * short runs with fewer users than cases.
 *
 * <p>Stopping: the engine clears its running flag and interrupts the thread. Both
 * {@link Thread#sleep} and {@link HttpClient#send} react to the interrupt, so a user never
 * outlives its engine.
 */
final class VirtualUser implements Runnable {

    private final HttpClient client;
    private final List<HttpRequest> requests;
    private final RunRecorder recorder;
    private final ThinkTime thinkTime;
    private final BooleanSupplier running;
    private int next;

    /**
     * @param client     shared HTTP client (thread-safe)
     * @param requests   one prepared request per template
     * @param recorder   where results go
     * @param thinkTime  pause between requests
     * @param running    whether the engine is still running
     * @param firstIndex the case this user starts with
     */
    VirtualUser(HttpClient client, List<HttpRequest> requests, RunRecorder recorder, ThinkTime thinkTime,
                BooleanSupplier running, int firstIndex) {
        this.client = client;
        this.requests = requests;
        this.recorder = recorder;
        this.thinkTime = thinkTime;
        this.running = running;
        this.next = firstIndex % requests.size();
    }

    @Override
    public void run() {
        recorder.activeUsers().incrementAndGet();
        try {
            while (running.getAsBoolean()) {
                sendOne(next);
                next = (next + 1) % requests.size();
                Thread.sleep(thinkTime.next(ThreadLocalRandom.current()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            recorder.activeUsers().decrementAndGet();
        }
    }

    private void sendOne(int index) throws InterruptedException {
        long start = System.nanoTime();
        try {
            HttpResponse<byte[]> response = client.send(requests.get(index), HttpResponse.BodyHandlers.ofByteArray());
            long micros = (System.nanoTime() - start) / 1000;
            recorder.record(index, response.statusCode(), micros, response.body().length, null);
        } catch (IOException e) {
            if (!running.getAsBoolean()) {
                return;
            }
            long micros = (System.nanoTime() - start) / 1000;
            recorder.record(index, ResponseClassifier.NO_RESPONSE, micros, 0, describe(e));
        }
    }

    private static String describe(IOException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
