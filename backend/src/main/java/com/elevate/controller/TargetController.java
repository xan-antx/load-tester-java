package com.elevate.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.elevate.analysis.probe.SanityResult;
import com.elevate.dto.AnalyzeResponse;
import com.elevate.dto.UrlRequest;
import com.elevate.service.TargetAnalysisService;

import jakarta.validation.Valid;

/** Target analysis: reachability probe and API/website classification. */
@RestController
@RequestMapping("/api")
public class TargetController {

    private final TargetAnalysisService analysis;

    /** @param analysis the analysis service */
    public TargetController(TargetAnalysisService analysis) {
        this.analysis = analysis;
    }

    /** Probes the URL: 200 when it is well formed, 400 otherwise. */
    @PostMapping("/sanity-check")
    public ResponseEntity<SanityResult> sanityCheck(@Valid @RequestBody UrlRequest request) {
        SanityResult result = analysis.sanityCheck(request.trimmedUrl());
        return ResponseEntity.status(result.validFormat() ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(result);
    }

    /** Probes and classifies the URL: 200 when reachable, 400 otherwise. */
    @PostMapping("/analyze")
    public ResponseEntity<AnalyzeResponse> analyze(@Valid @RequestBody UrlRequest request) {
        TargetAnalysisService.Analysis result = analysis.analyze(request.trimmedUrl(), request.headers());
        return ResponseEntity.status(result.ok() ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(result.body());
    }
}
