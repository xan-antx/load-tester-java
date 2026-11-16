package com.elevate.model;

import java.util.Arrays;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What a correct API should answer to an edge case: {@code accept} means a 2xx is right
 * (valid input), {@code reject} means a 4xx other than 429 is right (invalid input).
 */
public enum Expectation {
    ACCEPT, REJECT;

    /** {@code "accept"} or {@code "reject"}. */
    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    /**
     * Parses the JSON form.
     *
     * @param value {@code "accept"} or {@code "reject"}
     * @return the expectation, or empty for any other value (including {@code null})
     */
    public static Optional<Expectation> fromWire(Object value) {
        return Arrays.stream(values()).filter(e -> e.wire().equals(value)).findFirst();
    }
}
