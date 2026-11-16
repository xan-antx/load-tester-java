package com.elevate.dto;

import com.elevate.model.Expectation;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;

/**
 * A case confirmed for a run (generated or custom), as sent back to the browser and then
 * included in the start request.
 *
 * @param label        unique label
 * @param category     generator category or {@code custom}
 * @param classifiedAs custom cases: the category the diff matched
 * @param description  what was changed
 * @param payload      the JSON body
 * @param expect       expected answer
 */
public record ConfirmedCase(String label, String category,
                            @JsonInclude(JsonInclude.Include.NON_NULL) String classifiedAs,
                            String description, JsonNode payload, Expectation expect) {
}
