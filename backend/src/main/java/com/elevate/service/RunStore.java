package com.elevate.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.elevate.engine.EngineResult;
import com.elevate.model.RepeatSeries;
import com.elevate.model.RunStatus;
import com.elevate.model.SplitGroup;
import com.elevate.model.TestRun;
import com.elevate.repository.RepeatSeriesRepository;
import com.elevate.repository.SplitGroupRepository;
import com.elevate.repository.TestRunRepository;

/**
 * Every database write of the run lifecycle, each in its own short transaction. The run
 * threads call these methods; they never hold an entity across calls.
 */
@Service
@Transactional
public class RunStore {

    /** Message stored on runs the backend could not finish. */
    public static final String INTERRUPTED_REASON = "The backend stopped while this run was in progress "
            + "(restart or crash), so it never finished. Start it again.";

    private final TestRunRepository runs;
    private final SplitGroupRepository groups;
    private final RepeatSeriesRepository series;
    private final Clock clock;

    /**
     * @param runs   single runs
     * @param groups split groups
     * @param series repeat series
     * @param clock  for timestamps
     */
    public RunStore(TestRunRepository runs, SplitGroupRepository groups, RepeatSeriesRepository series, Clock clock) {
        this.runs = runs;
        this.groups = groups;
        this.series = series;
        this.clock = clock;
    }

    /** What kind of run an id names. */
    public enum Kind { JOB, GROUP, SERIES }

    /** Stores a new single run. */
    public PreparedRun saveJob(TestRun run) {
        runs.save(run);
        return new PreparedRun(run.getId(), List.of());
    }

    /** Stores a new split group with its children. */
    public PreparedRun saveGroup(SplitGroup group, List<TestRun> children) {
        groups.save(group);
        children.forEach(group::addChild);
        runs.saveAll(children);
        return new PreparedRun(group.getId(), children.stream().map(TestRun::getId).toList());
    }

    /** Stores a new repeat series. */
    public void saveSeries(RepeatSeries newSeries) {
        series.save(newSeries);
    }

    /** Appends a repeat's id to its series. */
    public void addSeriesRun(String seriesId, String runId) {
        series.findById(seriesId).orElseThrow().addRun(runId);
    }

    /** What the engine needs for one job. */
    @Transactional(readOnly = true)
    public JobPlan loadPlan(String jobId) {
        TestRun run = runs.findById(jobId).orElseThrow();
        return new JobPlan(run.getId(), run.getJobType(), run.getTargetUrl(), run.getTargetPath(), run.getCases(),
                run.getUsers(), run.getSpawnRate(), run.getDurationSeconds(), run.getCapacityPlan(), run.getSlo());
    }

    /** Changes a job's status (and keeps its group's stored status in step). */
    public void changeJobStatus(String jobId, RunStatus status, String message) {
        TestRun run = runs.findById(jobId).orElseThrow();
        run.changeStatus(status, message, clock.instant());
        if (run.getGroup() != null) {
            refresh(run.getGroup());
        }
    }

    /** Stores a job's results and its final status. */
    public void saveResults(String jobId, EngineResult result, RunStatus status, String message) {
        TestRun run = runs.findById(jobId).orElseThrow();
        run.storeResults(result.results(), result.steps());
        run.changeStatus(status, message, clock.instant());
        if (run.getGroup() != null) {
            refresh(run.getGroup());
        }
    }

    /** Re-derives and stores a group's status from its children. */
    public void refreshGroup(String groupId) {
        refresh(groups.findById(groupId).orElseThrow());
    }

    private void refresh(SplitGroup group) {
        RunStatus derived = group.deriveStatus();
        if (derived != group.getStatus()) {
            group.changeStatus(derived, null, clock.instant());
        }
    }

    /** Changes a series' status. */
    public void changeSeriesStatus(String seriesId, RunStatus status, String message) {
        series.findById(seriesId).orElseThrow().changeStatus(status, message, clock.instant());
    }

    /** The status of a job or group, or empty when no such run exists. */
    @Transactional(readOnly = true)
    public Optional<RunStatus> statusOf(String runId) {
        Optional<SplitGroup> group = groups.findById(runId);
        if (group.isPresent()) {
            return Optional.of(group.get().deriveStatus());
        }
        return runs.findById(runId).map(TestRun::getStatus);
    }

    /** What an id names, if anything. */
    @Transactional(readOnly = true)
    public Optional<Kind> kindOf(String id) {
        if (series.existsById(id)) {
            return Optional.of(Kind.SERIES);
        }
        if (groups.existsById(id)) {
            return Optional.of(Kind.GROUP);
        }
        return runs.existsById(id) ? Optional.of(Kind.JOB) : Optional.empty();
    }

    /** A series' repeat ids and status. */
    @Transactional(readOnly = true)
    public RepeatSeriesState seriesState(String seriesId) {
        RepeatSeries s = series.findById(seriesId).orElseThrow();
        return new RepeatSeriesState(s.getStatus(), s.getRunIds());
    }

    /** A group's child ids. */
    @Transactional(readOnly = true)
    public List<String> childIds(String groupId) {
        return groups.findById(groupId).orElseThrow().getChildren().stream().map(TestRun::getId).toList();
    }

    /**
     * Marks every run left queued or running by a previous process as interrupted.
     *
     * @return how many runs, groups and series were marked
     */
    public int markLeftoversInterrupted() {
        List<RunStatus> active = List.of(RunStatus.QUEUED, RunStatus.RUNNING);
        return runs.markInterrupted(active, INTERRUPTED_REASON, clock.instant())
                + groups.markInterrupted(active, INTERRUPTED_REASON, clock.instant())
                + series.markInterrupted(active, INTERRUPTED_REASON, clock.instant());
    }

    /**
     * @param status the series' status
     * @param runIds its repeats so far
     */
    public record RepeatSeriesState(RunStatus status, List<String> runIds) {
    }
}
