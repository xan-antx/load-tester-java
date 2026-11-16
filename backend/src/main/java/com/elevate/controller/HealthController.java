package com.elevate.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.elevate.dto.HealthResponse;

/** Liveness check, polled by the frontend header and by Docker health checks. */
@RestController
public class HealthController {

    /** {@code {"status": "ok"}}. */
    @GetMapping({"/api/health", "/health"})
    public HealthResponse health() {
        return HealthResponse.OK;
    }
}
