package com.elevate.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** 404: no run (or other resource) has the requested id. */
public class NotFoundException extends ApiException {

    /** @param message e.g. {@code run 'abc' not found} */
    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message, Map.of());
    }
}
