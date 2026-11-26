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
 * A split run (above 50 users) as the API reports it: the merged results of its engine
 * instances, plus each instance in {@code child_jobs}.
 *
 * @param groupId               id
 * @param targetUrl             scheme and host of the target
 * @param jobType               api or website
 * @param users                 total virtual users
 * @param spawnRate             users started per second (per instance)
 * @param durationSeconds       planned length
 * @param childCount            number of engine instances
 * @param status                derived from the children
 * @param error                 why it failed
 * @param createdAt             creation time (UTC)
 * @param completedAt           end time (UTC)
 * @param isBaseline            the baseline of its target
 * @param headerNames           names of the extra request headers
 * @param aggregatedStatsCsv    merged statistics
 * @param aggregatedFailuresCsv merged failures
 * @param aggregatedHistoryCsv  merged timeline
 * @param caseExpectations      expected answer per case
 * @param slo                   thresholds
 * @param mode                  always fixed (capacity runs are never split)
 * @param capacityPlan          always {@code null} (capacity runs are never split)
 * @param childJobs             the engine instances
 * @param p95Exact              always {@code true}: merged HdrHistograms give the exact p95
 * @param analysis              failure summary, outcome summary, verdict (unwrapped)
 */
public record GroupView(String groupId, String targetUrl, JobType jobType, int users, int spawnRate,
                        int durationSeconds, int childCount, RunStatus status, String error, String createdAt,
                        String completedAt, boolean isBaseline, List<String> headerNames, String aggregatedStatsCsv,
                        String aggregatedFailuresCsv, String aggregatedHistoryCsv,
                        Map<String, Expectation> caseExpectations, Slo slo, RunMode mode, CapacityPlan capacityPlan,
                        List<ChildJobView> childJobs,
                        boolean p95Exact, @JsonUnwrapped RunAnalysis analysis) implements RunView {

    @Override
    @JsonIgnore
    public String id() {
        return groupId;
    }
}
