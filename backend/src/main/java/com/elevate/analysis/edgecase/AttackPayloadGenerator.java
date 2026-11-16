package com.elevate.analysis.edgecase;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Known attack strings put into text fields, one case per string ({@code <prefix>_<field>_<i>}).
 * The two concrete strategies, {@link SqlInjectionGenerator} and {@link XssGenerator}, only
 * supply their payloads and wording: this abstract class is the shared template.
 */
public abstract class AttackPayloadGenerator implements EdgeCaseGenerator {

    /** Label prefix, e.g. {@code sqli}. */
    protected abstract String prefix();

    /** The attack strings. */
    protected abstract List<String> payloads();

    /** What the attack is, e.g. {@code SQL injection attempt}. */
    protected abstract String attackName();

    @Override
    public final List<EdgeCase> generate(String field, FieldType type, ObjectNode sample) {
        if (type != FieldType.STR) {
            return List.of();
        }
        List<EdgeCase> cases = new ArrayList<>();
        List<String> attacks = payloads();
        for (int i = 0; i < attacks.size(); i++) {
            cases.add(EdgeCaseGenerator.reject(prefix() + "_" + field + "_" + i, "known_attack",
                    attackName() + " in '" + field + "'",
                    EdgeCaseGenerator.withValue(sample, field, JsonNodeFactory.instance.stringNode(attacks.get(i)))));
        }
        return cases;
    }
}
