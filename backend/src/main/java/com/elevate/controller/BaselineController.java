package com.elevate.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.elevate.dto.BaselineLookupResponse;
import com.elevate.dto.BaselineRequest;
import com.elevate.dto.BaselineResponse;
import com.elevate.dto.RegressionsResponse;
import com.elevate.service.BaselineService;

/** Baselines per target and regression flags. */
@RestController
@RequestMapping("/api")
public class BaselineController {

    private final BaselineService baselines;

    /** @param baselines the baseline service */
    public BaselineController(BaselineService baselines) {
        this.baselines = baselines;
    }

    /** Makes a completed run the baseline of its target. */
    @PostMapping("/baseline")
    public BaselineResponse setBaseline(@RequestBody BaselineRequest request) {
        return baselines.set(request.id());
    }

    /** The baseline of a target. */
    @GetMapping("/baseline")
    public BaselineLookupResponse getBaseline(@RequestParam(name = "target_url", required = false) String targetUrl) {
        return baselines.lookup(targetUrl);
    }

    /** A run compared with its target's baseline (or with {@code baseline_id}). */
    @GetMapping("/regressions/{id}")
    public RegressionsResponse regressions(@PathVariable String id,
                                           @RequestParam(name = "baseline_id", required = false) String baselineId) {
        return baselines.regressions(id, baselineId);
    }
}
