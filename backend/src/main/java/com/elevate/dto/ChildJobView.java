package com.elevate.dto;

import com.elevate.model.RunStatus;

/**
 * One engine instance of a split run, as listed in {@code child_jobs}.
 *
 * @param jobId       id
 * @param status      status
 * @param users       its share of the users
 * @param createdAt   creation time (UTC)
 * @param completedAt end time (UTC)
 * @param error       why it failed
 * @param statsCsv    its own statistics
 * @param failuresCsv its own failures
 * @param historyCsv  its own timeline
 */
public record ChildJobView(String jobId, RunStatus status, int users, String createdAt, String completedAt,
                           String error, String statsCsv, String failuresCsv, String historyCsv) {
}
