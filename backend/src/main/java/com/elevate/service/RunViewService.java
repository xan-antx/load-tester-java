package com.elevate.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.elevate.analysis.compare.CaseComparison;
import com.elevate.analysis.compare.SeriesSummary;
import com.elevate.analysis.stats.LocustCsv;
import com.elevate.analysis.stats.RunResults;
import com.elevate.analysis.stats.StatsTable;
import com.elevate.dto.ChildJobView;
import com.elevate.dto.CompareResponse;
import com.elevate.dto.GroupView;
import com.elevate.dto.HistoryResponse;
import com.elevate.dto.JobView;
import com.elevate.dto.PairCompareResponse;
import com.elevate.dto.RunView;
import com.elevate.dto.SeriesView;
import com.elevate.dto.Timestamps;
import com.elevate.exception.BadRequestException;
import com.elevate.exception.NotFoundException;
import com.elevate.model.Expectation;
import com.elevate.model.MeasuredRun;
import com.elevate.model.RepeatSeries;
import com.elevate.model.RunStatus;
import com.elevate.model.SplitGroup;
import com.elevate.model.TestRun;
import com.elevate.repository.RepeatSeriesRepository;
import com.elevate.repository.SplitGroupRepository;
import com.elevate.repository.TestRunRepository;

import tools.jackson.databind.JsonNode;

/** The read side of the API: run status, run history and comparisons. */
@Service
@Transactional(readOnly = true)
public class RunViewService {

    private final RunSnapshots snapshots;
    private final TestRunRepository runs;
    private final SplitGroupRepository groups;
    private final RepeatSeriesRepository series;

    /**
     * @param snapshots loads runs with their results
     * @param runs      single runs
     * @param groups    split groups
     * @param series    repeat series
     */
    public RunViewService(RunSnapshots snapshots, TestRunRepository runs, SplitGroupRepository groups,
                          RepeatSeriesRepository series) {
        this.snapshots = snapshots;
        this.runs = runs;
        this.groups = groups;
        this.series = series;
    }

    /**
     * The status of any run: a series, a split group or a single job.
     *
     * @throws NotFoundException when nothing has this id
     */
    public RunView status(String id) {
        Optional<RepeatSeries> repeatSeries = series.findById(id);
        if (repeatSeries.isPresent()) {
            return seriesView(repeatSeries.get());
        }
        return snapshots.find(id).map(RunViewService::view)
                .orElseThrow(() -> new NotFoundException("job_id not found"));
    }

    /** The 50 newest single runs and the 50 newest split groups. */
    public HistoryResponse history() {
        List<HistoryResponse.Row> jobRows = runs.findTop50ByGroupIsNullOrderByCreatedAtDesc().stream()
                .map(run -> row(snapshots.summary(run), run.getId(), null)).toList();
        List<HistoryResponse.Row> groupRows = groups.findTop50ByOrderByCreatedAtDesc().stream()
                .map(group -> row(snapshots.summary(group), null, group.getId())).toList();
        return new HistoryResponse(jobRows, groupRows);
    }

    /**
     * Compares 2–4 runs.
     *
     * @param jobIds the {@code job_ids} JSON value
     * @throws BadRequestException unless it is a list of 2 to 4 non-empty strings
     */
    public CompareResponse compare(JsonNode jobIds) {
        if (jobIds == null || !jobIds.isArray() || jobIds.size() < 2 || jobIds.size() > 4) {
            throw new BadRequestException("job_ids must be a list of 2 to 4 run ids");
        }
        List<RunView> views = new ArrayList<>();
        List<StatsTable> tables = new ArrayList<>();
        for (JsonNode node : jobIds) {
            if (!node.isString() || node.stringValue().isEmpty()) {
                throw new BadRequestException("job_ids must be a list of 2 to 4 run ids");
            }
        }
        for (JsonNode node : jobIds) {
            String id = node.stringValue();
            RunSnapshot snapshot = snapshots.find(id).orElseThrow(() -> new NotFoundException("run '" + id + "' not found"));
            views.add(view(snapshot));
            tables.add(snapshot.stats());
        }
        return new CompareResponse(views, CaseComparison.of(tables));
    }

    /** The older two-run comparison ({@code job_id_a} / {@code job_id_b}). */
    public PairCompareResponse comparePair(String jobIdA, String jobIdB) {
        if (jobIdA == null || jobIdA.isEmpty() || jobIdB == null || jobIdB.isEmpty()) {
            throw new BadRequestException("job_id_a and job_id_b are required");
        }
        RunSnapshot a = snapshots.find(jobIdA)
                .orElseThrow(() -> new NotFoundException("job_id_a '" + jobIdA + "' not found"));
        RunSnapshot b = snapshots.find(jobIdB)
                .orElseThrow(() -> new NotFoundException("job_id_b '" + jobIdB + "' not found"));
        return new PairCompareResponse(view(a), view(b));
    }

    /** The view of a single run or split group. */
    static RunView view(RunSnapshot s) {
        MeasuredRun run = s.run();
        RunResults r = s.results();
        if (run instanceof SplitGroup group) {
            return new GroupView(group.getId(), group.getTargetUrl(), group.getJobType(), group.getUsers(),
                    group.getSpawnRate(), group.getDurationSeconds(), group.getChildCount(), s.status(), group.getError(),
                    Timestamps.format(group.getCreatedAt()), Timestamps.format(group.getCompletedAt()), group.isBaseline(),
                    names(group.getHeaderNames()), LocustCsv.stats(s.stats()), LocustCsv.failures(r.failures()),
                    LocustCsv.history(r.timeline()), s.expectations(), group.getSlo(), group.getMode(), null,
                    group.getChildren().stream().map(RunViewService::child).toList(), true, s.analysis());
        }
        TestRun job = (TestRun) run;
        return new JobView(job.getId(), job.getTargetUrl(), job.getJobType(), job.getUsers(), job.getSpawnRate(),
                job.getDurationSeconds(), s.status(), job.getError(), job.getGroup() == null ? null : job.getGroup().getId(),
                Timestamps.format(job.getCreatedAt()), Timestamps.format(job.getCompletedAt()), job.isBaseline(),
                names(job.getHeaderNames()), LocustCsv.stats(s.stats()), LocustCsv.failures(r.failures()),
                LocustCsv.history(r.timeline()), s.expectations(), job.getSlo(), job.getMode(), job.getCapacityPlan(),
                s.analysis());
    }

    /** A child of a split group, with the same fields as a run row of the Python version. */
    private static ChildJobView child(TestRun child) {
        RunResults r = child.loadResults(true);
        Map<String, Expectation> expectations = new LinkedHashMap<>();
        child.getCases().stream().filter(c -> c.getExpect() != null)
                .forEach(c -> expectations.put(c.getLabel(), c.getExpect()));
        return new ChildJobView(child.getId(), child.getTargetUrl(), child.getUsers(), child.getSpawnRate(),
                child.getDurationSeconds(), child.getStatus(), LocustCsv.stats(StatsTable.of(r)),
                LocustCsv.failures(r.failures()), LocustCsv.history(r.timeline()), child.getError(),
                child.getGroup() == null ? null : child.getGroup().getId(), Timestamps.format(child.getCreatedAt()),
                Timestamps.format(child.getCompletedAt()), child.isBaseline(), names(child.getHeaderNames()),
                expectations.isEmpty() ? null : expectations, child.getSlo(), child.getMode(), child.getCapacityPlan());
    }

    /** Header names, or {@code null} when none were sent (as the Python version stores them). */
    private static List<String> names(List<String> headerNames) {
        return headerNames.isEmpty() ? null : headerNames;
    }

    private SeriesView seriesView(RepeatSeries s) {
        List<SeriesView.Repeat> repeats = new ArrayList<>();
        List<SeriesSummary.Repeat> finished = new ArrayList<>();
        for (String runId : s.getRunIds()) {
            Optional<RunSnapshot> found = snapshots.findSummary(runId);
            if (found.isEmpty()) {
                continue;
            }
            RunSnapshot snapshot = found.get();
            repeats.add(new SeriesView.Repeat(runId, snapshot.status(), snapshot.isGroup(), snapshot.verdictText(),
                    snapshot.analysis().problemRate()));
            if (snapshot.status() == RunStatus.COMPLETED) {
                finished.add(new SeriesSummary.Repeat(snapshot.stats(), snapshot.analysis().problemRate()));
            }
        }
        SeriesSummary summary = s.getStatus().isTerminal() && !finished.isEmpty() ? SeriesSummary.of(finished) : null;
        return new SeriesView(s.getId(), s.getTargetUrl(), s.getJobType(), s.getUsers(), s.getSpawnRate(),
                s.getDurationSeconds(), s.getRepeatCount(), s.getRunIds(), s.getStatus(), s.getError(), names(s.getHeaderNames()),
                Timestamps.format(s.getCreatedAt()), Timestamps.format(s.getCompletedAt()), repeats, summary, true);
    }

    private static HistoryResponse.Row row(RunSnapshot s, String jobId, String groupId) {
        MeasuredRun run = s.run();
        return new HistoryResponse.Row(jobId, groupId, run.getTargetUrl(), run.getUsers(), s.status(),
                Timestamps.format(run.getCreatedAt()), Timestamps.format(run.getCompletedAt()), run.isBaseline(),
                run.getSlo(), run.getMode(), s.verdictText());
    }
}
