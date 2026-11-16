package com.elevate.analysis.edgecase;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A value of the wrong type ({@code wrong_type_<field>}): text for a number or boolean,
 * a number for text. Scalars only.
 */
public final class WrongTypeGenerator implements EdgeCaseGenerator {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    @Override
    public List<EdgeCase> generate(String field, FieldType type, ObjectNode sample) {
        if (!type.isScalar()) {
            return List.of();
        }
        JsonNode wrong = switch (type) {
            case INT, FLOAT -> NODES.stringNode("not_a_number");
            case STR -> NODES.numberNode(12345);
            default -> NODES.stringNode("not_a_bool");
        };
        return List.of(EdgeCaseGenerator.reject("wrong_type_" + field, "type_mismatch",
                "'" + field + "' given a " + FieldType.of(wrong).pythonName() + " instead of " + type.pythonName(),
                EdgeCaseGenerator.withValue(sample, field, wrong)));
    }
}
