package com.elevate.dto;

import tools.jackson.databind.JsonNode;

/**
 * Body of {@code POST /api/baseline}.
 *
 * @param id the run to make the baseline of its target (must be a string)
 */
public record BaselineRequest(JsonNode id) {
}
