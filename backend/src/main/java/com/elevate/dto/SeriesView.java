package com.elevate.dto;

import java.util.List;

import com.elevate.analysis.compare.SeriesSummary;
import com.elevate.model.JobType;
import com.elevate.model.RunStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A repeat series as the API reports it.
 *
 * @param seriesId        id
 * @param targetUrl       scheme and host of the target
 * @param jobType         api or website
 * @param users           users of each repeat
 * @param spawnRate       spawn rate of each repeat
 * @param durationSeconds length of each repeat
 * @param repeatCount     planned repeats
 * @param runIds          ids of the repeats started so far
 * @param status          status
 * @param error           why it failed
 * @param headerNames     names of the extra request headers
 * @param createdAt       creation time (UTC)
 * @param completedAt     end time (UTC)
 * @param runs            each repeat's status and verdict
 * @param seriesSummary   statistics across the completed repeats, once the series has stopped
 * @param p95Exact        always {@code true}: split repeats merge their histograms exactly
 */
public record SeriesView(String seriesId, String targetUrl, JobType jobType, int users, int spawnRate,
                         int durationSeconds, int repeatCount, List<String> runIds, RunStatus status, String error,
                         List<String> headerNames, String createdAt, String completedAt, List<Repeat> runs,
                         SeriesSummary seriesSummary, boolean p95Exact) implements RunView {

    @Override
    @JsonIgnore
    public String id() {
        return seriesId;
    }

    /**
     * @param id          the repeat's run id
     * @param status      its status
     * @param isGroup     it was a split run
     * @param sloVerdict  {@code PASS}, {@code FAIL} or {@code null}
     * @param problemRate its problem rate in percent
     */
    public record Repeat(String id, RunStatus status, boolean isGroup, String sloVerdict, Double problemRate) {
    }
}
