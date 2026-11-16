package com.elevate.analysis.edgecase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.elevate.model.Expectation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generates every edge case of a sample request: first the sample itself
 * ({@code valid_sample}, expects 2xx), then, field by field, the cases of each strategy in
 * order. The order of the strategies fixes the order of the cases, which matches the
 * Python version exactly.
 */
public final class EdgeCaseSuite {

    private final List<EdgeCaseGenerator> generators;

    /** @param generators the strategies, in the order their cases should appear */
    public EdgeCaseSuite(List<EdgeCaseGenerator> generators) {
        this.generators = List.copyOf(generators);
    }

    /** Factory method: the standard strategies in the standard order. */
    public static EdgeCaseSuite standard() {
        return new EdgeCaseSuite(List.of(new MissingFieldGenerator(), new NullValueGenerator(), new WrongTypeGenerator(),
                new BoundaryValueGenerator(), new VeryLongStringGenerator(), new SqlInjectionGenerator(),
                new XssGenerator()));
    }

    /**
     * @param sample a valid request body (a JSON object)
     * @return the sample case followed by every generated case
     */
    public List<EdgeCase> generate(ObjectNode sample) {
        List<EdgeCase> cases = new ArrayList<>();
        cases.add(new EdgeCase("valid_sample", "valid_input", "The sample request exactly as given", sample.deepCopy(),
                Expectation.ACCEPT));
        for (Map.Entry<String, JsonNode> field : sample.properties()) {
            FieldType type = FieldType.of(field.getValue());
            generators.forEach(g -> cases.addAll(g.generate(field.getKey(), type, sample)));
        }
        return cases;
    }

    /** The labels {@link #generate(ObjectNode)} would produce. */
    public Set<String> labels(ObjectNode sample) {
        return generate(sample).stream().map(EdgeCase::label).collect(Collectors.toSet());
    }
}
