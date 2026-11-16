package com.elevate.engine;

import java.util.List;

import com.elevate.analysis.capacity.StepRecord;
import com.elevate.analysis.stats.RunResults;

/**
 * What one engine instance measured.
 *
 * @param results    per-case measurements, failures and the per-second timeline
 * @param steps      step records of a capacity run (empty otherwise)
 * @param stopReason why it stopped
 */
public record EngineResult(RunResults results, List<StepRecord> steps, StopReason stopReason) {

    /** Defensive copy. */
    public EngineResult {
        steps = List.copyOf(steps);
    }
}
