package com.elevate.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code /api/start-load-test} and {@code /api/start-website-load-test}. Missing
 * numbers get the Python defaults (5 users, spawn rate 1, 30 s, 1 repeat); Bean Validation
 * then checks their ranges.
 *
 * @param url             the target
 * @param confirmedCases  cases from {@code /api/confirm-selection} (API runs)
 * @param iOwnTarget      must be {@code true}
 * @param users           virtual users, 1–1000
 * @param spawnRate       users started per second, 1–20
 * @param durationSeconds length of the run, 1–300
 * @param headers         optional request headers
 * @param repeat          run it this many times back to back, 1–5
 * @param slo             optional thresholds
 * @param mode            {@code fixed} or {@code capacity}
 * @param capacity        the step plan of a capacity run
 * @param sitemapRaw      the sitemap XML from {@code /api/analyze} (website runs)
 */
public record StartRunRequest(
        @NotBlank(message = "url (string) is required") String url,
        JsonNode confirmedCases,
        JsonNode iOwnTarget,
        @Min(value = 1, message = USERS) @Max(value = 1000, message = USERS) Integer users,
        @Min(value = 1, message = SPAWN) @Max(value = 20, message = SPAWN) Integer spawnRate,
        @Min(value = 1, message = DURATION) @Max(value = 300, message = DURATION) Integer durationSeconds,
        JsonNode headers,
        @Min(value = 1, message = REPEAT) @Max(value = 5, message = REPEAT) Integer repeat,
        JsonNode slo,
        String mode,
        JsonNode capacity,
        String sitemapRaw) {

    static final String USERS = "users must be between 1 and 1000";
    static final String SPAWN = "spawn_rate must be between 1 and 20";
    static final String DURATION = "duration_seconds must be between 1 and 300";
    static final String REPEAT = "repeat must be between 1 and 5";

    /** Fills in the defaults. */
    public StartRunRequest {
        users = users == null ? 5 : users;
        spawnRate = spawnRate == null ? 1 : spawnRate;
        durationSeconds = durationSeconds == null ? 30 : durationSeconds;
        repeat = repeat == null ? 1 : repeat;
        mode = mode == null ? "fixed" : mode;
    }

    /** Whether the request says {@code "i_own_target": true} (exactly the boolean). */
    public boolean ownershipConfirmed() {
        return iOwnTarget != null && iOwnTarget.isBoolean() && iOwnTarget.booleanValue();
    }
}
