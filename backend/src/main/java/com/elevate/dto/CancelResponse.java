package com.elevate.dto;

/**
 * 202 body of {@code /api/cancel/{id}}.
 *
 * @param id     the run
 * @param status always {@code cancelling}
 */
public record CancelResponse(String id, String status) {
}
