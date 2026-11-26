package com.elevate.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.elevate.analysis.compare.RegressionReport;
import com.elevate.dto.BaselineLookupResponse;
import com.elevate.dto.BaselineResponse;
import com.elevate.dto.RegressionsResponse;
import com.elevate.dto.Timestamps;
import com.elevate.exception.BadRequestException;
import com.elevate.exception.NotFoundException;
import com.elevate.model.MeasuredRun;
import com.elevate.model.RunStatus;
import com.elevate.model.SplitGroup;
import com.elevate.model.TestRun;
import com.elevate.repository.SplitGroupRepository;
import com.elevate.repository.TestRunRepository;

import tools.jackson.databind.JsonNode;

/**
 * Baselines and regression flags: each target can have one known-good run, and every new
 * run of that target is compared with it.
 */
@Service
@Transactional
public class BaselineService {

    private final TestRunRepository runs;
    private final SplitGroupRepository groups;
    private final RunSnapshots snapshots;

    /**
     * @param runs      single runs
     * @param groups    split groups
     * @param snapshots loads runs with their results
     */
    public BaselineService(TestRunRepository runs, SplitGroupRepository groups, RunSnapshots snapshots) {
        this.runs = runs;
        this.groups = groups;
        this.snapshots = snapshots;
    }

    /**
     * Makes a completed run the only baseline of its target.
     *
     * @param id the {@code id} JSON value
     */
    public BaselineResponse set(JsonNode id) {
        if (id == null || !id.isString() || id.stringValue().isEmpty()) {
            throw new BadRequestException("id (string) is required");
        }
        String runId = id.stringValue();
        RunSnapshot snapshot = snapshots.findSummary(runId)
                .orElseThrow(() -> new NotFoundException("run '" + runId + "' not found"));
        if (snapshot.status() != RunStatus.COMPLETED) {
            throw new BadRequestException("only a completed run can be the baseline");
        }
        MeasuredRun run = snapshot.run();
        runs.clearBaseline(run.getTargetUrl());
        groups.clearBaseline(run.getTargetUrl());
        MeasuredRun fresh = run instanceof SplitGroup
                ? groups.findById(runId).orElseThrow() : runs.findById(runId).orElseThrow();
        fresh.setBaseline(true);
        return new BaselineResponse(runId, run.getTargetUrl());
    }

    /** The baseline of a target, if any. */
    @Transactional(readOnly = true)
    public BaselineLookupResponse lookup(String targetUrl) {
        if (targetUrl == null || targetUrl.isEmpty()) {
            throw new BadRequestException("target_url query parameter is required");
        }
        return new BaselineLookupResponse(targetUrl, baselineId(targetUrl).orElse(null));
    }

    /**
     * Compares a run with its target's baseline (or with {@code baselineIdParam}).
     *
     * @param runId           the run
     * @param baselineIdParam an explicit baseline, or {@code null}
     */
    @Transactional(readOnly = true)
    public RegressionsResponse regressions(String runId, String baselineIdParam) {
        RunSnapshot run = snapshots.findSummary(runId)
                .orElseThrow(() -> new NotFoundException("run '" + runId + "' not found"));
        String targetUrl = run.run().getTargetUrl();
        String baselineId;
        if (baselineIdParam != null && !baselineIdParam.isEmpty()) {
            if (snapshots.findSummary(baselineIdParam).isEmpty()) {
                throw new NotFoundException("baseline run '" + baselineIdParam + "' not found");
            }
            baselineId = baselineIdParam;
        } else {
            baselineId = baselineId(targetUrl).orElse(null);
        }
        boolean isCurrent = runId.equals(baselineId);
        if (baselineId == null || isCurrent) {
            return new RegressionsResponse(targetUrl, baselineId, isCurrent, null, null, true);
        }
        RunSnapshot baseline = snapshots.findSummary(baselineId).orElseThrow();
        MeasuredRun b = baseline.run();
        return new RegressionsResponse(targetUrl, baselineId, false,
                new RegressionsResponse.Baseline(baselineId, b.getUsers(), Timestamps.format(b.getCreatedAt()),
                        baseline.isGroup()),
                RegressionReport.detect(baseline.stats(), run.stats()), true);
    }

    private Optional<String> baselineId(String targetUrl) {
        return groups.findFirstByTargetUrlAndBaselineTrue(targetUrl).map(SplitGroup::getId)
                .or(() -> runs.findFirstByTargetUrlAndBaselineTrue(targetUrl).map(TestRun::getId));
    }
}
