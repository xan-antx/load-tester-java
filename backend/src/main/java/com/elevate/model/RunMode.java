package com.elevate.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** {@code fixed}: one load level for a duration; {@code capacity}: step load to find the breaking point. */
public enum RunMode {
    FIXED, CAPACITY;

    /** {@code "fixed"} or {@code "capacity"}. */
    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }
}
