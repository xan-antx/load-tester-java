package com.elevate.analysis.edgecase;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CustomCaseClassifierTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CustomCaseClassifier classifier = new CustomCaseClassifier(EdgeCaseSuite.standard());
    private final JsonNode sample = json("{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30}");

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void removedFieldIsMissingFieldCategory() {
        CustomCaseResult r = classifier.classify(sample, json("{\"username\":\"demo\"}"), "no_pw");
        assertThat(r.valid()).isTrue();
        assertThat(r.category()).isEqualTo("missing_field");
        assertThat(r.description()).isEqualTo("'password' removed; 'expiresInMins' removed");
        assertThat(r.changes()).hasSize(2);
    }

    @Test
    void typeChangeAndNullAreRecognised() {
        assertThat(classifier.classify(sample, json("{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":\"30\"}"),
                "t").category()).isEqualTo("type_mismatch");
        assertThat(classifier.classify(sample, json("{\"username\":null,\"password\":\"demo123\",\"expiresInMins\":30}"),
                "n").category()).isEqualTo("null_value");
    }

    @Test
    void mixedChangesAreCustom() {
        CustomCaseResult r = classifier.classify(sample,
                json("{\"username\":\"admin\",\"password\":\"demo123\",\"expiresInMins\":30,\"role\":\"root\"}"), "mixed");
        assertThat(r.category()).isEqualTo("custom");
        assertThat(r.description()).isEqualTo("'username' changed from \"demo\" to \"admin\"; unknown field 'role' added (\"root\")");
    }

    @Test
    void intAndLongWithTheSameValueAreNotADifference() {
        CustomCaseResult r = classifier.classify(sample,
                json("{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30}"), "same");
        assertThat(r.valid()).isFalse();
        assertThat(r.reason()).isEqualTo("Not an edge case — identical to the sample");
    }

    @Test
    void labelRules() {
        JsonNode payload = json("{\"username\":\"x\"}");
        assertThat(classifier.classify(sample, payload, "Bad Label").reason())
                .isEqualTo("Label must be 1-40 characters: lowercase letters, digits or _");
        assertThat(classifier.classify(sample, payload, "missing_username").reason())
                .isEqualTo("Label 'missing_username' is already used by a generated case");
        assertThat(classifier.classify(sample, payload, null).valid()).isTrue();
    }

    @Test
    void sampleAndPayloadMustBeObjects() {
        assertThat(classifier.classify(json("[1]"), json("{}"), "a").reason()).isEqualTo("Sample input must be a JSON object");
        assertThat(classifier.classify(sample, json("[1]"), "a").reason()).isEqualTo("Payload must be a JSON object");
        assertThat(classifier.classify(sample, null, "a").valid()).isFalse();
    }

    @Test
    void longValuesAreShortenedInDescriptions() {
        String longName = "x".repeat(100);
        CustomCaseResult r = classifier.classify(sample,
                json("{\"username\":\"" + longName + "\",\"password\":\"demo123\",\"expiresInMins\":30}"), "long");
        assertThat(r.description()).endsWith("...");
    }
}
