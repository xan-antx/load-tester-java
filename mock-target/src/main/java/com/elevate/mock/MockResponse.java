package com.elevate.mock;

import java.util.Map;

/**
 * One answer of the mock: an HTTP status and a JSON object body.
 *
 * @param status HTTP status code
 * @param body   fields of the JSON body
 */
public record MockResponse(int status, Map<String, Object> body) {

    /** A response whose body is {@code {"message": message}}. */
    public static MockResponse message(int status, String message) {
        return new MockResponse(status, Map.of("message", message));
    }
}
