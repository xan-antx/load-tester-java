package com.elevate.dto;

import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

/**
 * Body of {@code /api/sanity-check} and {@code /api/analyze}.
 *
 * @param url     the target
 * @param headers optional request headers for the probe ({@code {"Name": "value"}})
 */
public record UrlRequest(@NotBlank(message = "url is required") String url, JsonNode headers) {

    /** The URL without surrounding spaces. */
    public String trimmedUrl() {
        return url.strip();
    }
}
