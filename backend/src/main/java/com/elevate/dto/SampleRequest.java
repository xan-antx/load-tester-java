package com.elevate.dto;

import tools.jackson.databind.JsonNode;

/**
 * Body of {@code /api/generate-edge-cases}.
 *
 * @param sampleInput a valid request body (must be a JSON object)
 */
public record SampleRequest(JsonNode sampleInput) {
}
