package com.elevate.analysis.edgecase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks a user-written test case against the sample. A custom case is only useful if it
 * really differs from the sample, so the two are diffed and every change is described. If
 * all changes are of one kind the generator knows (removed fields, nulls, type changes) the
 * case gets that category; anything else is {@code custom}.
 */
public final class CustomCaseClassifier {

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9_]{1,40}$");
    private static final int SHORT_VALUE = 40;
    private static final Map<String, String> SINGLE_KIND_CATEGORY = Map.of(
            "removed", "missing_field", "set_null", "null_value", "type_change", "type_mismatch");

    private final EdgeCaseSuite suite;

    /** @param suite used to check that a label is not taken by a generated case */
    public CustomCaseClassifier(EdgeCaseSuite suite) {
        this.suite = suite;
    }

    /**
     * @param sample  the sample request
     * @param payload the user's request body
     * @param label   the user's label, or {@code null} to skip label checks
     * @return the classification
     */
    public CustomCaseResult classify(JsonNode sample, JsonNode payload, String label) {
        if (!(sample instanceof ObjectNode sampleObject)) {
            return CustomCaseResult.invalid("Sample input must be a JSON object");
        }
        if (payload == null || !payload.isObject()) {
            return CustomCaseResult.invalid("Payload must be a JSON object");
        }
        if (label != null) {
            if (!LABEL.matcher(label).matches()) {
                return CustomCaseResult.invalid("Label must be 1-40 characters: lowercase letters, digits or _");
            }
            if (suite.labels(sampleObject).contains(label)) {
                return CustomCaseResult.invalid("Label '" + label + "' is already used by a generated case");
            }
        }
        List<CustomCaseResult.Change> changes = diff(sampleObject, payload);
        if (changes.isEmpty()) {
            return CustomCaseResult.invalid("Not an edge case — identical to the sample");
        }
        Set<String> kinds = changes.stream().map(CustomCaseResult.Change::kind).collect(Collectors.toSet());
        String category = kinds.size() == 1 ? SINGLE_KIND_CATEGORY.getOrDefault(kinds.iterator().next(), "custom")
                : "custom";
        String description = changes.stream().map(CustomCaseClassifier::describe).collect(Collectors.joining("; "));
        return new CustomCaseResult(true, null, category, description, changes);
    }

    private static List<CustomCaseResult.Change> diff(ObjectNode sample, JsonNode payload) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        List<CustomCaseResult.Change> changes = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : sample.properties()) {
            String field = entry.getKey();
            JsonNode original = entry.getValue();
            JsonNode now = payload.get(field);
            if (now == null) {
                changes.add(new CustomCaseResult.Change(field, "removed", null, null));
            } else if (now.isNull() && !original.isNull()) {
                changes.add(new CustomCaseResult.Change(field, "set_null", null, null));
            } else if (!original.isNull() && FieldType.of(now) != FieldType.of(original)) {
                changes.add(new CustomCaseResult.Change(field, "type_change",
                        nodes.stringNode(FieldType.of(original).pythonName()), nodes.stringNode(FieldType.of(now).pythonName())));
            } else if (!sameValue(original, now)) {
                changes.add(new CustomCaseResult.Change(field, "value_change", original, now));
            }
        }
        for (Map.Entry<String, JsonNode> entry : payload.properties()) {
            if (!sample.has(entry.getKey())) {
                changes.add(new CustomCaseResult.Change(entry.getKey(), "added", null, entry.getValue()));
            }
        }
        return changes;
    }

    /** Equality where 30 and 30L are the same number (Jackson keeps int and long nodes apart). */
    private static boolean sameValue(JsonNode a, JsonNode b) {
        if (a.isIntegralNumber() && b.isIntegralNumber()) {
            return a.bigIntegerValue().equals(b.bigIntegerValue());
        }
        if (a.isNumber() && b.isNumber()) {
            return a.doubleValue() == b.doubleValue();
        }
        return a.equals(b);
    }

    private static String describe(CustomCaseResult.Change change) {
        String field = change.field();
        return switch (change.kind()) {
            case "removed" -> "'" + field + "' removed";
            case "set_null" -> "'" + field + "' set to null";
            case "type_change" -> "'" + field + "' changed type from " + change.from().stringValue() + " to "
                    + change.to().stringValue();
            case "value_change" -> "'" + field + "' changed from " + shorten(change.from()) + " to " + shorten(change.to());
            default -> "unknown field '" + field + "' added (" + shorten(change.to()) + ")";
        };
    }

    private static String shorten(JsonNode value) {
        String text = value.toString();
        return text.length() <= SHORT_VALUE ? text : text.substring(0, SHORT_VALUE - 3) + "...";
    }
}
