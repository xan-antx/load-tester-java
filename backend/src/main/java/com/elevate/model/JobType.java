package com.elevate.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a run loads: an API endpoint with edge cases, or website pages. */
public enum JobType {
    API, WEBSITE;

    /** {@code "api"} or {@code "website"}. */
    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }
}
