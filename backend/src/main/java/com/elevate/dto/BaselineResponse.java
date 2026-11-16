package com.elevate.dto;

/**
 * Body of {@code POST /api/baseline}.
 *
 * @param baselineId the new baseline
 * @param targetUrl  its target
 */
public record BaselineResponse(String baselineId, String targetUrl) {
}
