package com.elevate.analysis.edgecase;

import java.util.List;

import tools.jackson.databind.node.ObjectNode;

/** The field removed entirely ({@code missing_<field>}); applies to every field. */
public final class MissingFieldGenerator implements EdgeCaseGenerator {

    @Override
    public List<EdgeCase> generate(String field, FieldType type, ObjectNode sample) {
        ObjectNode missing = sample.deepCopy();
        missing.remove(field);
        return List.of(EdgeCaseGenerator.reject("missing_" + field, "missing_field",
                "Request with '" + field + "' removed entirely", missing));
    }
}
