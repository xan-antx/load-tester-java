package com.elevate.analysis.edgecase;

import java.util.List;

import com.elevate.model.Expectation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strategy for one kind of edge case. Each implementation knows how to break one field of
 * the sample request in its own way (remove it, null it, give it the wrong type, ...);
 * {@link EdgeCaseSuite} runs every strategy over every field.
 */
public interface EdgeCaseGenerator {

    /**
     * @param field  the field name
     * @param type   the field's type in the sample
     * @param sample the sample request (never modified: implementations work on copies)
     * @return the cases for this field (empty when the strategy does not apply to the type)
     */
    List<EdgeCase> generate(String field, FieldType type, ObjectNode sample);

    /**
     * Helper for implementations: a copy of the sample with one field replaced.
     *
     * @param sample the sample
     * @param field  the field to replace
     * @param value  its new value
     * @return the modified copy
     */
    static ObjectNode withValue(ObjectNode sample, String field, JsonNode value) {
        ObjectNode copy = sample.deepCopy();
        copy.set(field, value);
        return copy;
    }

    /** Helper for implementations: a case that a correct API must reject. */
    static EdgeCase reject(String label, String category, String description, JsonNode payload) {
        return new EdgeCase(label, category, description, payload, Expectation.REJECT);
    }
}
