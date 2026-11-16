package com.elevate.service;

import java.util.Map;

import com.elevate.analysis.stats.RunResults;
import com.elevate.analysis.stats.StatsTable;
import com.elevate.dto.RunAnalysis;
import com.elevate.model.Expectation;
import com.elevate.model.MeasuredRun;
import com.elevate.model.RunStatus;
import com.elevate.model.SplitGroup;

/**
 * A single run or split group loaded together with its results and conclusions, so the
 * status view, history, comparison, baseline check and report all compute them the same way.
 *
 * @param run          the entity (only valid inside the transaction that loaded it)
 * @param status       its status (derived from the children for a group)
 * @param results      measured (and, for a group, merged) results
 * @param stats        their statistics, or {@code null} without results
 * @param analysis     the conclusions
 * @param expectations expected answer per case, or {@code null} without any
 */
public record RunSnapshot(MeasuredRun run, RunStatus status, RunResults results, StatsTable stats,
                          RunAnalysis analysis, Map<String, Expectation> expectations) {

    /** The run's id. */
    public String id() {
        return run.getId();
    }

    /** Whether it is a split group. */
    public boolean isGroup() {
        return run instanceof SplitGroup;
    }

    /** The SLO verdict text ({@code PASS}/{@code FAIL}), or {@code null}. */
    public String verdictText() {
        return analysis.sloVerdict() == null ? null : analysis.sloVerdict().verdict();
    }
}
