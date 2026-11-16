package com.elevate.dto;

import tools.jackson.databind.JsonNode;

/**
 * Body of {@code /api/confirm-selection}. Kept as JSON trees because each field has its own
 * error message when it has the wrong shape.
 *
 * @param sampleInput    the sample request
 * @param selectedLabels labels of the generated cases to send
 * @param customCases    user-written cases {@code [{"label", "payload", "expect"?}]}
 * @param expectations   overrides {@code {label: "accept" | "reject"}}
 */
public record ConfirmSelectionRequest(JsonNode sampleInput, JsonNode selectedLabels, JsonNode customCases,
                                      JsonNode expectations) {
}
