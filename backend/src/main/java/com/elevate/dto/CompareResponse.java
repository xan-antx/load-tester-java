package com.elevate.dto;

import java.util.List;

import com.elevate.analysis.compare.CaseComparison;

/**
 * Body of {@code /api/compare-jobs} with {@code job_ids}.
 *
 * @param jobs           the runs, in the order asked for
 * @param caseComparison every case lined up across the runs
 */
public record CompareResponse(List<RunView> jobs, CaseComparison caseComparison) implements CompareResult {
}
