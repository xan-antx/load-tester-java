package com.elevate.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** 400: the request is invalid; the message says which field and why. */
public class BadRequestException extends ApiException {

    /** @param message what is wrong with the request */
    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message, Map.of());
    }

    /**
     * @param message what is wrong with the request
     * @param details extra fields of the error body
     */
    protected BadRequestException(String message, Map<String, Object> details) {
        super(HttpStatus.BAD_REQUEST, message, details);
    }
}
