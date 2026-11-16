package com.elevate.analysis.edgecase;

import com.elevate.model.Expectation;

import tools.jackson.databind.JsonNode;

/**
 * One request body to send, and what a correct API should answer to it.
 *
 * @param label       unique name, e.g. {@code missing_username}
 * @param category    e.g. {@code missing_field}, {@code boundary_value}
 * @param description what was changed, in words
 * @param payload     the JSON body
 * @param expect      {@code accept} (2xx is right) or {@code reject} (4xx is right)
 */
public record EdgeCase(String label, String category, String description, JsonNode payload, Expectation expect) {
}
