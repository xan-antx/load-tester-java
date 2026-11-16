package com.elevate.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

/**
 * The same configuration run several times, one after another. Each repeat is an ordinary
 * run (single or split); the series only records their ids in order.
 */
@Entity
@Table(name = "repeat_series")
public class RepeatSeries extends AbstractRun {

    @Column(nullable = false)
    private int repeatCount;

    @ElementCollection
    @CollectionTable(name = "series_run", joinColumns = @JoinColumn(name = "series_id"))
    @OrderColumn(name = "position")
    @Column(name = "run_id", length = 36)
    private List<String> runIds = new ArrayList<>();

    /** For JPA. */
    protected RepeatSeries() {
    }

    /**
     * @param id          unique id
     * @param settings    target and load settings of every repeat
     * @param repeatCount how many repeats
     * @param now         creation time
     */
    public RepeatSeries(String id, RunSettings settings, int repeatCount, Instant now) {
        super(id, settings, now);
        this.repeatCount = repeatCount;
    }

    /** Records the next repeat's id. */
    public void addRun(String runId) {
        runIds.add(runId);
    }

    public int getRepeatCount() {
        return repeatCount;
    }

    public List<String> getRunIds() {
        return List.copyOf(runIds);
    }
}
