package com.elevate.dto;

import java.util.List;

import com.elevate.analysis.edgecase.EdgeCase;

/**
 * Body of {@code /api/generate-edge-cases}.
 *
 * @param totalCases number of cases
 * @param cases      the cases, sample first
 */
public record EdgeCasesResponse(int totalCases, List<EdgeCase> cases) {
}
