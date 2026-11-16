package com.elevate.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.elevate.analysis.slo.Slo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * A run above 50 users, executed as several engine instances in parallel (its children,
 * at most 50 users each). Its results are the exact merge of its children's.
 */
@Entity
@Table(name = "split_group")
public class SplitGroup extends MeasuredRun {

    @Column(nullable = false)
    private int childCount;

    @OneToMany(mappedBy = "group")
    @OrderBy("createdAt")
    private List<TestRun> children = new ArrayList<>();

    /** For JPA. */
    protected SplitGroup() {
    }

    /**
     * @param id         unique id
     * @param settings   target and total load settings
     * @param childCount number of engine instances
     * @param slo        thresholds, or {@code null}
     * @param now        creation time
     */
    public SplitGroup(String id, RunSettings settings, int childCount, Slo slo, Instant now) {
        super(id, settings, slo, null, now);
        this.childCount = childCount;
    }

    /** Adds a child run (both sides of the relation are kept in step). */
    public void addChild(TestRun child) {
        child.joinGroup(this);
        children.add(child);
    }

    public int getChildCount() {
        return childCount;
    }

    public List<TestRun> getChildren() {
        return List.copyOf(children);
    }

    /**
     * The group's status, derived from its children like the Python version: any failed →
     * failed; any interrupted → interrupted; all completed → completed; all completed or
     * cancelled → cancelled; any running → running; otherwise queued.
     */
    public RunStatus deriveStatus() {
        List<RunStatus> statuses = children.stream().map(TestRun::getStatus).toList();
        if (statuses.contains(RunStatus.FAILED)) {
            return RunStatus.FAILED;
        }
        if (statuses.contains(RunStatus.INTERRUPTED)) {
            return RunStatus.INTERRUPTED;
        }
        if (!statuses.isEmpty() && statuses.stream().allMatch(s -> s == RunStatus.COMPLETED)) {
            return RunStatus.COMPLETED;
        }
        if (!statuses.isEmpty() && statuses.stream().allMatch(s -> s == RunStatus.COMPLETED || s == RunStatus.CANCELLED)) {
            return RunStatus.CANCELLED;
        }
        return statuses.contains(RunStatus.RUNNING) ? RunStatus.RUNNING : RunStatus.QUEUED;
    }
}
