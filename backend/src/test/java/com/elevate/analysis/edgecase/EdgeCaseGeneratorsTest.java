package com.elevate.analysis.edgecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.elevate.model.Expectation;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One nested class per strategy: what it generates, for which types, with which expectation. */
class EdgeCaseGeneratorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ObjectNode sample = (ObjectNode) JSON.readTree(
            "{\"username\":\"demo\",\"age\":30,\"price\":9.5,\"active\":true,\"tags\":[1],\"meta\":{\"a\":1}}");

    @Nested
    class MissingField {
        @Test
        void removesTheFieldAndExpectsRejection() {
            List<EdgeCase> cases = new MissingFieldGenerator().generate("username", FieldType.STR, sample);
            assertThat(cases).singleElement().satisfies(c -> {
                assertThat(c.label()).isEqualTo("missing_username");
                assertThat(c.category()).isEqualTo("missing_field");
                assertThat(c.payload().has("username")).isFalse();
                assertThat(c.payload().has("age")).isTrue();
                assertThat(c.expect()).isEqualTo(Expectation.REJECT);
            });
            assertThat(sample.has("username")).as("the sample is never modified").isTrue();
        }

        @Test
        void appliesToNestedValuesToo() {
            assertThat(new MissingFieldGenerator().generate("meta", FieldType.OBJECT, sample)).hasSize(1);
        }
    }

    @Nested
    class NullValue {
        @Test
        void setsTheFieldToNull() {
            EdgeCase c = new NullValueGenerator().generate("tags", FieldType.ARRAY, sample).getFirst();
            assertThat(c.label()).isEqualTo("null_tags");
            assertThat(c.payload().get("tags").isNull()).isTrue();
            assertThat(c.description()).isEqualTo("'tags' set to null");
        }
    }

    @Nested
    class WrongType {
        @Test
        void textForNumbersAndBooleansNumberForText() {
            WrongTypeGenerator g = new WrongTypeGenerator();
            assertThat(g.generate("age", FieldType.INT, sample).getFirst().payload().get("age").stringValue())
                    .isEqualTo("not_a_number");
            assertThat(g.generate("price", FieldType.FLOAT, sample).getFirst().payload().get("price").stringValue())
                    .isEqualTo("not_a_number");
            assertThat(g.generate("active", FieldType.BOOL, sample).getFirst().payload().get("active").stringValue())
                    .isEqualTo("not_a_bool");
            EdgeCase text = g.generate("username", FieldType.STR, sample).getFirst();
            assertThat(text.payload().get("username").intValue()).isEqualTo(12345);
            assertThat(text.description()).isEqualTo("'username' given a int instead of str");
        }

        @Test
        void skipsNonScalars() {
            assertThat(new WrongTypeGenerator().generate("meta", FieldType.OBJECT, sample)).isEmpty();
        }
    }

    @Nested
    class BoundaryValue {
        @Test
        void numbersGetLargeNegativeAndZero() {
            List<EdgeCase> cases = new BoundaryValueGenerator().generate("age", FieldType.INT, sample);
            assertThat(cases).extracting(EdgeCase::label).containsExactly("very_large_age", "negative_age", "zero_age");
            assertThat(cases).extracting(EdgeCase::expect)
                    .containsExactly(Expectation.REJECT, Expectation.REJECT, Expectation.ACCEPT);
            assertThat(cases.getFirst().payload().get("age").longValue()).isEqualTo(1_000_000_000_000L);
            assertThat(cases.getFirst().description()).isEqualTo("'age' set to very large (1000000000000)");
        }

        @Test
        void textGetsTheEmptyString() {
            assertThat(new BoundaryValueGenerator().generate("username", FieldType.STR, sample))
                    .singleElement().satisfies(c -> assertThat(c.payload().get("username").stringValue()).isEmpty());
        }

        @Test
        void booleansGetNothing() {
            assertThat(new BoundaryValueGenerator().generate("active", FieldType.BOOL, sample)).isEmpty();
        }
    }

    @Nested
    class VeryLongString {
        @Test
        void fiveThousandCharacters() {
            EdgeCase c = new VeryLongStringGenerator().generate("username", FieldType.STR, sample).getFirst();
            assertThat(c.label()).isEqualTo("very_long_string_username");
            assertThat(c.payload().get("username").stringValue()).hasSize(5000);
            assertThat(c.category()).isEqualTo("boundary_value");
            assertThat(new VeryLongStringGenerator().generate("age", FieldType.INT, sample)).isEmpty();
        }
    }

    @Nested
    class Attacks {
        @Test
        void sqlInjectionOneCasePerPayload() {
            List<EdgeCase> cases = new SqlInjectionGenerator().generate("username", FieldType.STR, sample);
            assertThat(cases).extracting(EdgeCase::label).containsExactly("sqli_username_0", "sqli_username_1", "sqli_username_2");
            assertThat(cases.get(1).payload().get("username").stringValue()).isEqualTo("'; DROP TABLE users; --");
            assertThat(cases).allSatisfy(c -> assertThat(c.category()).isEqualTo("known_attack"));
        }

        @Test
        void xssOneCasePerPayload() {
            List<EdgeCase> cases = new XssGenerator().generate("username", FieldType.STR, sample);
            assertThat(cases).extracting(EdgeCase::label).containsExactly("xss_username_0", "xss_username_1");
            assertThat(cases.getFirst().description()).isEqualTo("XSS attempt in 'username'");
        }

        @Test
        void onlyTextFields() {
            assertThat(new SqlInjectionGenerator().generate("age", FieldType.INT, sample)).isEmpty();
            assertThat(new XssGenerator().generate("active", FieldType.BOOL, sample)).isEmpty();
        }
    }

    @Test
    void fieldTypesAreInferredLikePython() {
        assertThat(FieldType.of(sample.get("username"))).isEqualTo(FieldType.STR);
        assertThat(FieldType.of(sample.get("age"))).isEqualTo(FieldType.INT);
        assertThat(FieldType.of(sample.get("price"))).isEqualTo(FieldType.FLOAT);
        assertThat(FieldType.of(sample.get("active"))).isEqualTo(FieldType.BOOL);
        assertThat(FieldType.of(sample.get("tags"))).isEqualTo(FieldType.ARRAY);
        assertThat(FieldType.of(sample.get("meta"))).isEqualTo(FieldType.OBJECT);
        assertThat(FieldType.of(null)).isEqualTo(FieldType.NULL);
    }
}
