package com.elevate.exception;

import java.util.Map;

import org.springframework.http.HttpStatus;

/** 409: the request is valid but this server's mode cannot do it (queue mode refusals). */
public class ConflictException extends ApiException {

    /** @param message why it cannot be done here */
    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message, Map.of());
    }
}
