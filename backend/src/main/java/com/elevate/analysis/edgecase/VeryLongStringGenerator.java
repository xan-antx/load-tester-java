package com.elevate.analysis.edgecase;

import java.util.List;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A 5,000-character string ({@code very_long_string_<field>}) for text fields: catches
 * missing length checks (the mock target's deliberate bug accepts it with 200).
 */
public final class VeryLongStringGenerator implements EdgeCaseGenerator {

    /** Length of the generated string. */
    public static final int LENGTH = 5000;

    @Override
    public List<EdgeCase> generate(String field, FieldType type, ObjectNode sample) {
        if (type != FieldType.STR) {
            return List.of();
        }
        return List.of(EdgeCaseGenerator.reject("very_long_string_" + field, "boundary_value",
                "'" + field + "' set to very long string",
                EdgeCaseGenerator.withValue(sample, field, JsonNodeFactory.instance.stringNode("A".repeat(LENGTH)))));
    }
}
