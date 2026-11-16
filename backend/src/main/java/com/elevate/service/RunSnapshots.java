package com.elevate.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.elevate.analysis.stats.ResultMerger;
import com.elevate.analysis.stats.RunResults;
import com.elevate.analysis.stats.StatsTable;
import com.elevate.model.Expectation;
import com.elevate.model.RunCase;
import com.elevate.model.RunStatus;
import com.elevate.model.SplitGroup;
import com.elevate.model.TestRun;
import com.elevate.repository.SplitGroupRepository;
import com.elevate.repository.TestRunRepository;

/**
 * Loads {@link RunSnapshot}s: a run with its results and conclusions. A <i>summary</i>
 * snapshot skips the per-second timeline, which lists and verdicts do not need.
 */
@Component
@Transactional(readOnly = true)
public class RunSnapshots {

    private final TestRunRepository runs;
    private final SplitGroupRepository groups;

    /**
     * @param runs   single runs
     * @param groups split groups
     */
    public RunSnapshots(TestRunRepository runs, SplitGroupRepository groups) {
        this.runs = runs;
        this.groups = groups;
    }

    /** The run or group with this id, with everything. */
    public Optional<RunSnapshot> find(String id) {
        return find(id, true);
    }

    /** The run or group with this id, without its timeline. */
    public Optional<RunSnapshot> findSummary(String id) {
        return find(id, false);
    }

    /** A single run without its timeline. */
    public RunSnapshot summary(TestRun run) {
        return of(run, false);
    }

    /** A split group without its timeline. */
    public RunSnapshot summary(SplitGroup group) {
        return of(group, false);
    }

    private Optional<RunSnapshot> find(String id, boolean withTimeline) {
        Optional<SplitGroup> group = groups.findById(id);
        if (group.isPresent()) {
            return Optional.of(of(group.get(), withTimeline));
        }
        return runs.findById(id).map(run -> of(run, withTimeline));
    }

    private static RunSnapshot of(TestRun run, boolean withTimeline) {
        RunResults results = run.loadResults(withTimeline);
        StatsTable stats = StatsTable.of(results);
        Map<String, Expectation> expectations = expectations(run.getCases());
        return new RunSnapshot(run, run.getStatus(), results, stats,
                RunAnalyzer.analyze(results, stats, run.getStatus(), run.getSlo(), expectations != null,
                        run.getCapacityPlan(), run.getCapacitySteps()),
                expectations);
    }

    /**
     * Like the Python version, a group's results are merged once it has stopped normally
     * (completed, or cancelled with partial results).
     */
    private static RunSnapshot of(SplitGroup group, boolean withTimeline) {
        RunStatus status = group.deriveStatus();
        List<TestRun> children = group.getChildren();
        RunResults results = status == RunStatus.COMPLETED || status == RunStatus.CANCELLED
                ? ResultMerger.merge(children.stream().map(c -> c.loadResults(withTimeline)).filter(r -> !r.isEmpty()).toList())
                : RunResults.empty();
        StatsTable stats = StatsTable.of(results);
        Map<String, Expectation> expectations = children.isEmpty() ? null : expectations(children.getFirst().getCases());
        return new RunSnapshot(group, status, results, stats,
                RunAnalyzer.analyze(results, stats, status, group.getSlo(), expectations != null, null, List.of()),
                expectations);
    }

    /** {label: expectation} for the cases that carry one, or {@code null} when none do. */
    private static Map<String, Expectation> expectations(List<RunCase> cases) {
        Map<String, Expectation> map = new LinkedHashMap<>();
        cases.stream().filter(c -> c.getExpect() != null).forEach(c -> map.put(c.getLabel(), c.getExpect()));
        return map.isEmpty() ? null : map;
    }
}
