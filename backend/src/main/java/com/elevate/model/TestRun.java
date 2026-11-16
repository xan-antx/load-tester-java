package com.elevate.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.elevate.analysis.capacity.StepRecord;
import com.elevate.analysis.slo.Slo;
import com.elevate.analysis.stats.CaseMeasurement;
import com.elevate.analysis.stats.FailureCount;
import com.elevate.analysis.stats.RunResults;
import com.elevate.analysis.stats.TimelineSample;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * One load test executed by one engine instance: a stand-alone run, a repeat of a series,
 * or one child of a split group. Owns its cases and, once it has run, its results.
 */
@Entity
@Table(name = "test_run")
public class TestRun extends MeasuredRun {

    @Column(length = 2048)
    private String targetPath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private SplitGroup group;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "run_id")
    @OrderColumn(name = "position")
    private List<RunCase> cases = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "run_id")
    @OrderColumn(name = "position")
    private List<CaseResult> results = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "timeline_point", joinColumns = @JoinColumn(name = "run_id"))
    @OrderColumn(name = "position")
    private List<TimelinePoint> timeline = new ArrayList<>();

    @Lob
    @Convert(converter = StepRecordsConverter.class)
    private List<StepRecord> capacitySteps = new ArrayList<>();

    private Double measuredSeconds;

    /** For JPA. */
    protected TestRun() {
    }

    /**
     * A new queued run.
     *
     * @param id         unique id
     * @param settings   target and load settings
     * @param targetPath path the API cases are posted to ({@code null} for websites)
     * @param cases      what to send
     * @param slo        thresholds, or {@code null}
     * @param plan       capacity plan, or {@code null}
     * @param now        creation time
     */
    public TestRun(String id, RunSettings settings, String targetPath, List<RunCase> cases, Slo slo, CapacityPlan plan,
                   Instant now) {
        super(id, settings, slo, plan, now);
        this.targetPath = targetPath;
        this.cases = new ArrayList<>(cases);
    }

    /** Makes this run a child of a split group. */
    public void joinGroup(SplitGroup splitGroup) {
        this.group = splitGroup;
    }

    /**
     * Stores what the engine measured (replacing anything stored before).
     *
     * @param measured the results
     * @param steps    capacity step records
     */
    public void storeResults(RunResults measured, List<StepRecord> steps) {
        results.clear();
        for (CaseMeasurement m : measured.cases()) {
            List<FailureCount> own = measured.failures().stream().filter(f -> f.caseName().equals(m.name())).toList();
            results.add(CaseResult.of(m, own));
        }
        timeline.clear();
        measured.timeline().forEach(s -> timeline.add(TimelinePoint.from(s)));
        capacitySteps = new ArrayList<>(steps);
        measuredSeconds = measured.elapsedSeconds();
    }

    /**
     * The stored results (empty when the run has not produced any).
     *
     * @param withTimeline also decode the per-second timeline (not needed for verdicts)
     */
    public RunResults loadResults(boolean withTimeline) {
        if (results.isEmpty()) {
            return RunResults.empty();
        }
        List<CaseMeasurement> measurements = results.stream().map(CaseResult::toMeasurement).toList();
        List<FailureCount> failures = results.stream().flatMap(r -> r.toFailureCounts().stream()).toList();
        List<TimelineSample> samples = withTimeline ? timeline.stream().map(TimelinePoint::toSample).toList() : List.of();
        return new RunResults(measurements, failures, samples, measuredSeconds == null ? 0 : measuredSeconds);
    }

    public String getTargetPath() {
        return targetPath;
    }

    /** The group this run belongs to, or {@code null}. */
    public SplitGroup getGroup() {
        return group;
    }

    public List<RunCase> getCases() {
        return List.copyOf(cases);
    }

    /** Capacity step records (empty for fixed runs). */
    public List<StepRecord> getCapacitySteps() {
        return capacitySteps == null ? List.of() : List.copyOf(capacitySteps);
    }
}
