package com.elevate.analysis.edgecase;

import java.util.List;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** The field set to {@code null} ({@code null_<field>}); applies to every field. */
public final class NullValueGenerator implements EdgeCaseGenerator {

    @Override
    public List<EdgeCase> generate(String field, FieldType type, ObjectNode sample) {
        return List.of(EdgeCaseGenerator.reject("null_" + field, "null_value", "'" + field + "' set to null",
                EdgeCaseGenerator.withValue(sample, field, JsonNodeFactory.instance.nullNode())));
    }
}
