package com.elevate.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.elevate.dto.CompareRequest;
import com.elevate.dto.CompareResult;
import com.elevate.dto.HistoryResponse;
import com.elevate.service.RunViewService;

/** Run history and run comparison. */
@RestController
@RequestMapping("/api")
public class HistoryController {

    private final RunViewService views;

    /** @param views reads runs */
    public HistoryController(RunViewService views) {
        this.views = views;
    }

    /** The newest runs and split groups. */
    @GetMapping("/jobs")
    public HistoryResponse jobs() {
        return views.history();
    }

    /** Compares 2–4 runs ({@code job_ids}), or two runs in the older {@code job_id_a}/{@code job_id_b} form. */
    @PostMapping("/compare-jobs")
    public CompareResult compare(@RequestBody CompareRequest request) {
        if (request.jobIds() != null) {
            return views.compare(request.jobIds());
        }
        return views.comparePair(request.jobIdA(), request.jobIdB());
    }
}
