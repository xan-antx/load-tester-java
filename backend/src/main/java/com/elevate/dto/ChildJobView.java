package com.elevate.dto;

import java.util.List;
import java.util.Map;

import com.elevate.analysis.slo.Slo;
import com.elevate.model.CapacityPlan;
import com.elevate.model.Expectation;
import com.elevate.model.RunMode;
import com.elevate.model.RunStatus;

/**
 * One engine instance of a split run, as listed in {@code child_jobs}: the same fields as a
 * run row of the Python version (minus Locust's process output).
 *
 * @param jobId            id
 * @param targetUrl        scheme and host of the target
 * @param users            its share of the users
 * @param spawnRate        users started per second
 * @param durationSeconds  planned length
 * @param status           status
 * @param statsCsv         its own statistics
 * @param failuresCsv      its own failures
 * @param historyCsv       its own timeline
 * @param error            why it failed
 * @param groupId          the split group it belongs to
 * @param createdAt        creation time (UTC)
 * @param completedAt      end time (UTC)
 * @param isBaseline       always {@code false} (the group is the baseline, not its children)
 * @param headerNames      names of the extra request headers, or {@code null}
 * @param caseExpectations expected answer per case, or {@code null}
 * @param slo              thresholds, or {@code null}
 * @param mode             always fixed
 * @param capacityPlan     always {@code null}
 */
public record ChildJobView(String jobId, String targetUrl, int users, int spawnRate, int durationSeconds,
                           RunStatus status, String statsCsv, String failuresCsv, String historyCsv, String error,
                           String groupId, String createdAt, String completedAt, boolean isBaseline,
                           List<String> headerNames, Map<String, Expectation> caseExpectations, Slo slo, RunMode mode,
                           CapacityPlan capacityPlan) {
}
