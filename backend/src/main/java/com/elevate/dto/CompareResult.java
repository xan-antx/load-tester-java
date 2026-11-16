package com.elevate.dto;

/** Body of {@code /api/compare-jobs}: the 2–4 run form or the older two-run form. */
public sealed interface CompareResult permits CompareResponse, PairCompareResponse {
}
