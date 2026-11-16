package com.elevate.dto;

/**
 * Body of {@code GET /api/baseline}.
 *
 * @param targetUrl  the target asked about
 * @param baselineId its baseline, or {@code null}
 */
public record BaselineLookupResponse(String targetUrl, String baselineId) {
}
