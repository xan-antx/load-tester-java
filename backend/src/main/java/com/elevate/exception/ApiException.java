package com.elevate.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * Base class of the errors the API reports on purpose. Each carries the HTTP status and
 * optional extra fields for the JSON error body ({@code {"error": message, ...details}}).
 * {@link GlobalExceptionHandler} turns it into the response.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final Map<String, Object> details;

    /**
     * @param status  HTTP status of the response
     * @param message the {@code error} text, written for the user
     * @param details extra JSON fields (kept in order)
     */
    public ApiException(HttpStatus status, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /** HTTP status of the response. */
    public HttpStatus status() {
        return status;
    }

    /** Extra fields of the error body. */
    public Map<String, Object> details() {
        return details;
    }
}
