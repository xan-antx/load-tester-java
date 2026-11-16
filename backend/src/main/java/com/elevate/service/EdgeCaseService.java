package com.elevate.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.elevate.analysis.edgecase.CustomCaseClassifier;
import com.elevate.analysis.edgecase.CustomCaseResult;
import com.elevate.analysis.edgecase.EdgeCase;
import com.elevate.analysis.edgecase.EdgeCaseSuite;
import com.elevate.dto.ConfirmSelectionRequest;
import com.elevate.dto.ConfirmSelectionResponse;
import com.elevate.dto.ConfirmedCase;
import com.elevate.dto.EdgeCasesResponse;
import com.elevate.exception.BadRequestException;
import com.elevate.model.Expectation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Steps 2 and 3 of the workflow: generate edge cases, check custom ones, confirm the selection. */
@Service
public class EdgeCaseService {

    private final EdgeCaseSuite suite;
    private final CustomCaseClassifier classifier;

    /**
     * @param suite      the edge-case strategies
     * @param classifier custom-case validation
     */
    public EdgeCaseService(EdgeCaseSuite suite, CustomCaseClassifier classifier) {
        this.suite = suite;
        this.classifier = classifier;
    }

    /** Every edge case of a sample request. */
    public EdgeCasesResponse generate(JsonNode sampleInput) {
        List<EdgeCase> cases = suite.generate(requireSample(sampleInput));
        return new EdgeCasesResponse(cases.size(), cases);
    }

    /** Checks a user-written case ({@code valid: false} is a normal answer, not an error). */
    public CustomCaseResult validateCustomCase(JsonNode sampleInput, JsonNode payload, String label) {
        return classifier.classify(requireSample(sampleInput), payload, label == null ? "" : label);
    }

    /**
     * The cases to send: the selected generated cases (with expectation overrides applied)
     * and the custom cases, which are validated again here — the browser's check is not trusted.
     */
    public ConfirmSelectionResponse confirm(ConfirmSelectionRequest request) {
        ObjectNode sample = requireSample(request.sampleInput());
        JsonNode custom = present(request.customCases());
        if (custom != null && !custom.isArray()) {
            throw new BadRequestException("custom_cases must be a list");
        }
        boolean hasCustom = custom != null && !custom.isEmpty();
        Set<String> selected = selectedLabels(present(request.selectedLabels()), hasCustom);
        Map<String, Expectation> overrides = overrides(present(request.expectations()));

        List<EdgeCase> generated = suite.generate(sample);
        Set<String> known = generated.stream().map(EdgeCase::label).collect(Collectors.toSet());
        TreeSet<String> unknown = new TreeSet<>(selected);
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            throw new BadRequestException("Unknown labels: ["
                    + unknown.stream().map(l -> "'" + l + "'").collect(Collectors.joining(", ")) + "]");
        }
        List<ConfirmedCase> confirmed = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (EdgeCase c : generated) {
            if (selected.contains(c.label())) {
                confirmed.add(new ConfirmedCase(c.label(), c.category(), null, c.description(), c.payload(),
                        overrides.getOrDefault(c.label(), c.expect())));
                used.add(c.label());
            }
        }
        if (hasCustom) {
            for (JsonNode node : custom) {
                confirmed.add(customCase(sample, node, used));
            }
        }
        if (confirmed.isEmpty()) {
            throw new BadRequestException("No cases matched the selected labels");
        }
        return new ConfirmSelectionResponse(confirmed.size(), confirmed);
    }

    private ConfirmedCase customCase(ObjectNode sample, JsonNode node, Set<String> used) {
        if (!node.isObject()) {
            throw new BadRequestException("each custom case must be an object");
        }
        JsonNode labelNode = node.get("label");
        String label = labelNode != null && labelNode.isString() ? labelNode.stringValue() : null;
        JsonNode payload = node.get("payload");
        CustomCaseResult result = classifier.classify(sample, payload, label == null ? "" : label);
        if (!result.valid()) {
            throw new BadRequestException("Custom case '" + label + "': " + result.reason());
        }
        if (!used.add(label)) {
            throw new BadRequestException("Custom case '" + label + "': label used twice");
        }
        JsonNode expectNode = node.get("expect");
        Expectation expect = expectNode == null ? Expectation.REJECT
                : Expectation.fromWire(expectNode.isString() ? expectNode.stringValue() : null).orElseThrow(
                        () -> new BadRequestException("Custom case '" + label + "': expect must be \"accept\" or \"reject\""));
        return new ConfirmedCase(label, "custom", result.category(), result.description(), payload, expect);
    }

    private static Set<String> selectedLabels(JsonNode raw, boolean hasCustom) {
        if (raw == null && hasCustom) {
            return Set.of();
        }
        if (raw == null || !raw.isArray() || (!hasCustom && raw.isEmpty())) {
            throw new BadRequestException("selected_labels (list) is required");
        }
        Set<String> labels = new LinkedHashSet<>();
        raw.forEach(n -> labels.add(n.isString() ? n.stringValue() : n.toString()));
        return labels;
    }

    private static Map<String, Expectation> overrides(JsonNode raw) {
        if (raw == null) {
            return Map.of();
        }
        String message = "expectations must map case labels to \"accept\" or \"reject\"";
        if (!raw.isObject()) {
            throw new BadRequestException(message);
        }
        Map<String, Expectation> map = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : raw.properties()) {
            JsonNode v = e.getValue();
            map.put(e.getKey(), Expectation.fromWire(v.isString() ? v.stringValue() : null)
                    .orElseThrow(() -> new BadRequestException(message)));
        }
        return map;
    }

    private static ObjectNode requireSample(JsonNode sample) {
        if (sample instanceof ObjectNode object && !object.isEmpty()) {
            return object;
        }
        throw new BadRequestException("sample_input (object) is required");
    }

    /** {@code null} for a missing field or a JSON {@code null}. */
    private static JsonNode present(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }
}
