package com.elevate.dto;

import java.util.List;

import com.elevate.analysis.slo.Slo;
import com.elevate.model.RunMode;
import com.elevate.model.RunStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code /api/jobs}: the newest runs and split groups.
 *
 * @param jobs      single runs (not children of a group)
 * @param jobGroups split groups
 */
public record HistoryResponse(List<Row> jobs, List<Row> jobGroups) {

    /**
     * One run in the history. A row has {@code job_id} or {@code group_id}, not both.
     *
     * @param jobId       single runs
     * @param groupId     split groups
     * @param targetUrl   scheme and host
     * @param users       virtual users
     * @param status      status
     * @param createdAt   creation time (UTC)
     * @param completedAt end time (UTC)
     * @param isBaseline  the baseline of its target
     * @param slo         thresholds
     * @param mode        fixed or capacity
     * @param sloVerdict  {@code PASS}, {@code FAIL} or {@code null}
     */
    public record Row(@JsonInclude(JsonInclude.Include.NON_NULL) String jobId,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String groupId,
                      String targetUrl, int users, RunStatus status, String createdAt, String completedAt,
                      boolean isBaseline, Slo slo, RunMode mode, String sloVerdict) {
    }
}
