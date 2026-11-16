package com.elevate.dto;

import tools.jackson.databind.JsonNode;

/**
 * Body of {@code /api/validate-custom-case}.
 *
 * @param sampleInput the sample request
 * @param payload     the user's request body
 * @param label       the user's label
 */
public record CustomCaseRequest(JsonNode sampleInput, JsonNode payload, String label) {
}
