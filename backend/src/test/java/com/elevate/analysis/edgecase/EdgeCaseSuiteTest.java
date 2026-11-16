package com.elevate.analysis.edgecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.elevate.model.Expectation;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EdgeCaseSuiteTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final EdgeCaseSuite suite = EdgeCaseSuite.standard();
    private final ObjectNode login = (ObjectNode) JSON.readTree(
            "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30}");

    @Test
    void generatesTheSame27CasesInTheSameOrderAsPython() {
        List<String> labels = suite.generate(login).stream().map(EdgeCase::label).toList();
        assertThat(labels).hasSize(27);
        assertThat(labels.subList(0, 12)).containsExactly("valid_sample", "missing_username", "null_username",
                "wrong_type_username", "empty_string_username", "very_long_string_username", "sqli_username_0",
                "sqli_username_1", "sqli_username_2", "xss_username_0", "xss_username_1", "missing_password");
        assertThat(labels.subList(21, 27)).containsExactly("missing_expiresInMins", "null_expiresInMins",
                "wrong_type_expiresInMins", "very_large_expiresInMins", "negative_expiresInMins", "zero_expiresInMins");
    }

    @Test
    void theSampleItselfComesFirstAndMustBeAccepted() {
        EdgeCase first = suite.generate(login).getFirst();
        assertThat(first.category()).isEqualTo("valid_input");
        assertThat(first.expect()).isEqualTo(Expectation.ACCEPT);
        assertThat(first.payload()).isEqualTo(login);
    }

    @Test
    void labelsAreUnique() {
        assertThat(suite.labels(login)).hasSize(27);
    }

    @Test
    void theStrategyListDecidesWhatIsGenerated() {
        EdgeCaseSuite onlyMissing = new EdgeCaseSuite(List.of(new MissingFieldGenerator()));
        assertThat(onlyMissing.generate(login)).extracting(EdgeCase::label)
                .containsExactly("valid_sample", "missing_username", "missing_password", "missing_expiresInMins");
    }
}
