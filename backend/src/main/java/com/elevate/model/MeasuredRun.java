package com.elevate.model;

import java.time.Instant;

import com.elevate.analysis.slo.Slo;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;

/**
 * A run that produces its own results and can be judged, compared and made a baseline:
 * a single job ({@link TestRun}) or a split group ({@link SplitGroup}). Repeat series are
 * not: their repeats are.
 */
@MappedSuperclass
public abstract class MeasuredRun extends AbstractRun {

    @Column(nullable = false)
    private boolean baseline;

    @Embedded
    private SloSettings slo;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 16)
    private RunMode mode = RunMode.FIXED;

    @Embedded
    private CapacityPlan capacityPlan;

    /** For JPA. */
    protected MeasuredRun() {
    }

    /**
     * @param id       unique id
     * @param settings target and load settings
     * @param slo      thresholds, or {@code null}
     * @param plan     capacity plan, or {@code null} for a fixed run
     * @param now      creation time
     */
    protected MeasuredRun(String id, RunSettings settings, Slo slo, CapacityPlan plan, Instant now) {
        super(id, settings, now);
        this.slo = SloSettings.from(slo);
        this.capacityPlan = plan;
        this.mode = plan == null ? RunMode.FIXED : RunMode.CAPACITY;
    }

    /** Makes this run the baseline of its target (or stops it being one). */
    public void setBaseline(boolean isBaseline) {
        this.baseline = isBaseline;
    }

    public boolean isBaseline() {
        return baseline;
    }

    /** The SLO, or {@code null}. */
    public Slo getSlo() {
        return slo == null ? null : slo.toSlo();
    }

    public RunMode getMode() {
        return mode;
    }

    /** The capacity plan, or {@code null} for a fixed run. */
    public CapacityPlan getCapacityPlan() {
        return capacityPlan;
    }
}
