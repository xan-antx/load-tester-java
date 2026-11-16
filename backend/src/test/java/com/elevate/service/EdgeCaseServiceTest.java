package com.elevate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.elevate.analysis.edgecase.CustomCaseClassifier;
import com.elevate.analysis.edgecase.EdgeCaseSuite;
import com.elevate.dto.ConfirmSelectionRequest;
import com.elevate.dto.ConfirmSelectionResponse;
import com.elevate.dto.ConfirmedCase;
import com.elevate.exception.BadRequestException;
import com.elevate.model.Expectation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EdgeCaseServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SAMPLE = "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30}";
    private final EdgeCaseService service = new EdgeCaseService(EdgeCaseSuite.standard(),
            new CustomCaseClassifier(EdgeCaseSuite.standard()));

    private static JsonNode json(String text) {
        return text == null ? null : JSON.readTree(text);
    }

    private ConfirmSelectionResponse confirm(String labels, String custom, String expectations) {
        return service.confirm(new ConfirmSelectionRequest(json(SAMPLE), json(labels), json(custom), json(expectations)));
    }

    @Test
    void generatesTheCases() {
        assertThat(service.generate(json(SAMPLE)).totalCases()).isEqualTo(27);
        assertThatThrownBy(() -> service.generate(json("{}"))).hasMessage("sample_input (object) is required");
        assertThatThrownBy(() -> service.generate(json("[1]"))).hasMessage("sample_input (object) is required");
    }

    @Test
    void confirmsSelectedCasesWithOverrides() {
        ConfirmSelectionResponse r = confirm("[\"valid_sample\",\"missing_expiresInMins\"]", null,
                "{\"missing_expiresInMins\":\"accept\"}");
        assertThat(r.confirmedCount()).isEqualTo(2);
        assertThat(r.confirmedCases()).extracting(ConfirmedCase::expect).containsExactly(Expectation.ACCEPT, Expectation.ACCEPT);
    }

    @Test
    void customCasesAreRevalidatedAndDefaultToReject() {
        ConfirmSelectionResponse r = confirm(null, "[{\"label\":\"admin_login\",\"payload\":{\"username\":\"admin\","
                + "\"password\":\"x\",\"expiresInMins\":30}}]", null);
        ConfirmedCase c = r.confirmedCases().getFirst();
        assertThat(c.category()).isEqualTo("custom");
        assertThat(c.classifiedAs()).isEqualTo("custom");
        assertThat(c.expect()).isEqualTo(Expectation.REJECT);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
        "NULL|NULL|NULL|selected_labels (list) is required",
        "[]|NULL|NULL|selected_labels (list) is required",
        "[\"nope\",\"also_nope\"]|NULL|NULL|Unknown labels: ['also_nope', 'nope']",
        "[\"valid_sample\"]|{}|NULL|custom_cases must be a list",
        "[\"valid_sample\"]|NULL|{\"valid_sample\":\"maybe\"}|expectations must map case labels to \"accept\" or \"reject\"",
        "[]|[1]|NULL|each custom case must be an object",
        "[]|[{\"label\":\"x\",\"payload\":{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30}}]|NULL|Custom case 'x': Not an edge case — identical to the sample",
        "[]|[{\"label\":\"a\",\"payload\":{}},{\"label\":\"a\",\"payload\":{}}]|NULL|Custom case 'a': label used twice",
        "[]|[{\"label\":\"a\",\"payload\":{},\"expect\":\"x\"}]|NULL|Custom case 'a': expect must be \"accept\" or \"reject\"",
    })
    void rejectsBadSelections(String labels, String custom, String expectations, String message) {
        assertThatThrownBy(() -> confirm(labels, custom, expectations)).isInstanceOf(BadRequestException.class)
                .hasMessage(message);
    }

    @Test
    void validateCustomCaseTreatsAMissingLabelAsEmpty() {
        assertThat(service.validateCustomCase(json(SAMPLE), json("{\"username\":\"x\"}"), null).reason())
                .startsWith("Label must be");
    }
}
