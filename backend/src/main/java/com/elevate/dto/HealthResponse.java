package com.elevate.dto;

/**
 * Body of the health endpoints.
 *
 * @param status always {@code ok}
 */
public record HealthResponse(String status) {

    /** The only response. */
    public static final HealthResponse OK = new HealthResponse("ok");
}
