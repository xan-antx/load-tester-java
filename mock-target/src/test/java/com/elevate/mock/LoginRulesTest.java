package com.elevate.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class LoginRulesTest {

    private final LoginRules rules = new LoginRules();
    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode body(String text) {
        return json.readTree(text);
    }

    @Test
    void validCredentialsGetAToken() {
        MockResponse r = rules.evaluate(body("{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30}"));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).containsEntry("expiresInMins", 30L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"password\":\"demo123\"}",
        "{\"username\":null,\"password\":\"demo123\"}",
        "{\"username\":\"\",\"password\":\"demo123\"}",
        "{\"username\":12345,\"password\":\"demo123\"}",
        "{\"username\":\"demo\",\"password\":\"wrong\"}",
        "{\"username\":\"' OR '1'='1\",\"password\":\"demo123\"}",
        "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":-1}",
        "[1,2]"
    })
    void invalidInputIsRejectedWith400(String text) {
        assertThat(rules.evaluate(body(text)).status()).isEqualTo(400);
    }

    @Test
    void nonJsonBodyIsRejected() {
        assertThat(rules.evaluate(null).status()).isEqualTo(400);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"username\":\"demo\",\"password\":\"demo123\"}",
        "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":null}",
        "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":0}"
    })
    void optionalExpiryFallsBackToTheDefault(String text) {
        MockResponse r = rules.evaluate(body(text));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).containsEntry("expiresInMins", 60L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":\"not_a_number\"}",
        "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":1000000000000}",
        "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30.5}"
    })
    void deliberateCrashBugs(String text) {
        assertThatThrownBy(() -> rules.evaluate(body(text))).isInstanceOf(LoginRules.UnhandledInputException.class);
    }

    @Test
    void deliberateBugAcceptsAVeryLongUsername() {
        String longName = "A".repeat(5000);
        MockResponse r = rules.evaluate(body("{\"username\":\"" + longName + "\",\"password\":\"demo123\"}"));
        assertThat(r.status()).isEqualTo(200);
    }

    @Test
    void usernameOfExactly256CharactersIsStillChecked() {
        String name = "A".repeat(256);
        assertThat(rules.evaluate(body("{\"username\":\"" + name + "\",\"password\":\"demo123\"}")).status())
                .isEqualTo(400);
    }
}
