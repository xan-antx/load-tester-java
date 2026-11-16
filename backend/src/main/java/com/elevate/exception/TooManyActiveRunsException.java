package com.elevate.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/** 429: {@code MAX_ACTIVE_RUNS} load tests are already queued or running. */
public class TooManyActiveRunsException extends ApiException {

    /**
     * @param activeRuns    runs in progress now
     * @param maxActiveRuns the server's limit
     */
    public TooManyActiveRunsException(int activeRuns, int maxActiveRuns) {
        super(HttpStatus.TOO_MANY_REQUESTS, message(activeRuns, maxActiveRuns), details(activeRuns, maxActiveRuns));
    }

    private static String message(int active, int limit) {
        String running = active == 1 ? "A load test is" : active + " load tests are";
        return running + " already running, and this server runs at most " + limit + " at a time. "
                + "Wait for it to finish (or stop it), then start again.";
    }

    private static Map<String, Object> details(int active, int limit) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("busy", true);
        details.put("active_runs", active);
        details.put("max_active_runs", limit);
        return details;
    }
}
