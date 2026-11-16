package com.elevate.model;

import java.util.List;

/**
 * The settings every run starts with.
 *
 * @param targetUrl       scheme and host of the target, e.g. {@code http://127.0.0.1:8000}
 * @param jobType         API or website
 * @param users           virtual users
 * @param spawnRate       users started per second
 * @param durationSeconds length of the run
 * @param headerNames     names (never values) of the extra request headers
 */
public record RunSettings(String targetUrl, JobType jobType, int users, int spawnRate, int durationSeconds,
                          List<String> headerNames) {

    /** Defensive copy. */
    public RunSettings {
        headerNames = List.copyOf(headerNames);
    }

    /** The same settings with another user count (for the children of a split run). */
    public RunSettings withUsers(int newUsers) {
        return new RunSettings(targetUrl, jobType, newUsers, spawnRate, durationSeconds, headerNames);
    }
}
