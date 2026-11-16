package com.elevate.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.elevate.analysis.edgecase.CustomCaseResult;
import com.elevate.dto.ConfirmSelectionRequest;
import com.elevate.dto.ConfirmSelectionResponse;
import com.elevate.dto.CustomCaseRequest;
import com.elevate.dto.EdgeCasesResponse;
import com.elevate.dto.SampleRequest;
import com.elevate.service.EdgeCaseService;

/** Edge-case generation, custom-case validation and case selection. */
@RestController
@RequestMapping("/api")
public class EdgeCaseController {

    private final EdgeCaseService edgeCases;

    /** @param edgeCases the edge-case service */
    public EdgeCaseController(EdgeCaseService edgeCases) {
        this.edgeCases = edgeCases;
    }

    /** Every edge case of a sample request. */
    @PostMapping("/generate-edge-cases")
    public EdgeCasesResponse generate(@RequestBody SampleRequest request) {
        return edgeCases.generate(request.sampleInput());
    }

    /** Whether a user-written case is usable (always 200; see {@code valid}). */
    @PostMapping("/validate-custom-case")
    public CustomCaseResult validateCustomCase(@RequestBody CustomCaseRequest request) {
        return edgeCases.validateCustomCase(request.sampleInput(), request.payload(), request.label());
    }

    /** The cases to send in the load test. */
    @PostMapping("/confirm-selection")
    public ConfirmSelectionResponse confirm(@RequestBody ConfirmSelectionRequest request) {
        return edgeCases.confirm(request);
    }
}
