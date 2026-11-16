package com.elevate.dto;

/**
 * Body of {@code /api/compare-jobs} in the older {@code job_id_a} / {@code job_id_b} form.
 *
 * @param jobA first run
 * @param jobB second run
 */
public record PairCompareResponse(RunView jobA, RunView jobB) implements CompareResult {
}
