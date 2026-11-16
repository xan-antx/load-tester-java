package com.elevate.dto;

import java.util.List;
import java.util.Map;

import com.elevate.analysis.slo.Slo;
import com.elevate.model.CapacityPlan;
import com.elevate.model.Expectation;
import com.elevate.model.JobType;
import com.elevate.model.RunMode;
import com.elevate.model.RunStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * A single run as the API reports it.
 *
 * @param jobId            id
 * @param targetUrl        scheme and host of the target
 * @param jobType          api or website
 * @param users            virtual users
 * @param spawnRate        users started per second
 * @param durationSeconds  planned length
 * @param status           status
 * @param error            why it failed or was interrupted
 * @param groupId          the split group it belongs to, or {@code null}
 * @param createdAt        creation time (UTC)
 * @param completedAt      end time (UTC)
 * @param isBaseline       the baseline of its target
 * @param headerNames      names of the extra request headers
 * @param statsCsv         statistics (Locust's stats CSV format)
 * @param failuresCsv      failures (Locust's failures CSV format)
 * @param historyCsv       per-second timeline (Locust's history CSV format)
 * @param caseExpectations expected answer per case, or {@code null}
 * @param slo              thresholds, or {@code null}
 * @param mode             fixed or capacity
 * @param capacityPlan     capacity runs only
 * @param analysis         failure summary, outcome summary, verdicts (unwrapped into this object)
 */
public record JobView(String jobId, String targetUrl, JobType jobType, int users, int spawnRate, int durationSeconds,
                      RunStatus status, String error, String groupId, String createdAt, String completedAt,
                      boolean isBaseline, List<String> headerNames, String statsCsv, String failuresCsv,
                      String historyCsv, Map<String, Expectation> caseExpectations, Slo slo, RunMode mode,
                      CapacityPlan capacityPlan, @JsonUnwrapped RunAnalysis analysis) implements RunView {

    @Override
    @JsonIgnore
    public String id() {
        return jobId;
    }
}
