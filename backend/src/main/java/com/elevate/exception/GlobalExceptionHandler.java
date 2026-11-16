package com.elevate.exception;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import tools.jackson.databind.DatabindException;

/**
 * Turns every exception into the JSON error body the frontend expects:
 * {@code {"error": "<message>", ...extra fields}}. Deliberate errors ({@link ApiException})
 * keep their status and fields; anything unexpected becomes a 500 and is logged.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Errors raised on purpose by the services. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException e) {
        return body(e.status(), e.getMessage(), e.details());
    }

    /** Bean Validation failures on a request DTO: the first broken constraint's message. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .min(Comparator.comparing(FieldError::getField))
                .map(FieldError::getDefaultMessage)
                .orElse("invalid request");
        return body(HttpStatus.BAD_REQUEST, message, Map.of());
    }

    /** A body that is not JSON, or a field of the wrong type (e.g. {@code "users": "many"}). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        if (e.getCause() instanceof DatabindException databind && !databind.getPath().isEmpty()) {
            String field = databind.getPath().getLast().getPropertyName();
            return body(HttpStatus.BAD_REQUEST, field + " has the wrong type", Map.of());
        }
        return body(HttpStatus.BAD_REQUEST, "request body must be valid JSON", Map.of());
    }

    /** A POST without {@code Content-Type: application/json}. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.BAD_REQUEST, "request body must be JSON (Content-Type: application/json)", Map.of());
    }

    /** Unknown path. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", Map.of());
    }

    /** Known path, wrong method. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", Map.of());
    }

    /** Anything else is a bug: log it and say so. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        LOG.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_server_error",
                Map.of("detail", String.valueOf(e.getMessage())));
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message,
                                                            Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.putAll(details);
        return ResponseEntity.status(status).body(body);
    }
}
