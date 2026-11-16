package com.elevate.analysis.edgecase;

import java.util.ArrayList;
import java.util.List;

import com.elevate.model.Expectation;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Values at the edges of what a field can hold. Numbers: very large (10<sup>12</sup>) and
 * negative, which a validating API should refuse, and zero, which is usually valid
 * (expects 2xx). Text: the empty string. (A very long string has its own strategy.)
 */
public final class BoundaryValueGenerator implements EdgeCaseGenerator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final long VERY_LARGE = 1_000_000_000_000L;

    @Override
    public List<EdgeCase> generate(String field, FieldType type, ObjectNode sample) {
        List<EdgeCase> cases = new ArrayList<>();
        if (type.isNumeric()) {
            cases.add(number("very_large", VERY_LARGE, Expectation.REJECT, field, sample));
            cases.add(number("negative", -1, Expectation.REJECT, field, sample));
            cases.add(number("zero", 0, Expectation.ACCEPT, field, sample));
        } else if (type == FieldType.STR) {
            cases.add(EdgeCaseGenerator.reject("empty_string_" + field, "boundary_value",
                    "'" + field + "' set to empty string",
                    EdgeCaseGenerator.withValue(sample, field, NODES.stringNode(""))));
        }
        return cases;
    }

    private static EdgeCase number(String name, long value, Expectation expect, String field, ObjectNode sample) {
        return new EdgeCase(name + "_" + field, "boundary_value",
                "'" + field + "' set to " + name.replace('_', ' ') + " (" + value + ")",
                EdgeCaseGenerator.withValue(sample, field, NODES.numberNode(value)), expect);
    }
}
