package com.elevate.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.elevate.analysis.probe.ProbeAttempt;
import com.elevate.analysis.probe.SanityResult;
import com.elevate.analysis.probe.TypeDetection;
import com.elevate.dto.AnalyzeResponse;
import com.elevate.exception.TargetBlockedException;
import com.elevate.service.TargetAnalysisService;

@WebMvcTest({TargetController.class, HealthController.class})
class TargetControllerTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    TargetAnalysisService analysis;

    private static final SanityResult REACHABLE = new SanityResult(true, true, 405, "http://h/api/login", Map.of(), "OPTIONS",
            List.of(new ProbeAttempt("HEAD", 405, "", "POST")), true, "POST", null, null);

    @Test
    void health() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void analyzeReturnsSnakeCaseFieldsAndSignalArrays() throws Exception {
        when(analysis.analyze(anyString(), any())).thenReturn(new TargetAnalysisService.Analysis(true, new AnalyzeResponse(
                REACHABLE, new TypeDetection("api", 1.0, List.of(new TypeDetection.Signal("path_hint", "api", 0.6))),
                null, null, null)));
        mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\" http://h/api/login \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sanity.valid_format").value(true))
                .andExpect(jsonPath("$.sanity.method_not_allowed").value(true))
                .andExpect(jsonPath("$.sanity.attempts[0].status_code").value(405))
                .andExpect(jsonPath("$.type_detection.classification").value("api"))
                .andExpect(jsonPath("$.type_detection.signals[0][0]").value("path_hint"))
                .andExpect(jsonPath("$.type_detection.signals[0][2]").value(0.6))
                .andExpect(jsonPath("$.sitemap").doesNotExist());
    }

    @Test
    void unreachableTargetIs400WithTheSanityResult() throws Exception {
        SanityResult down = new SanityResult(true, false, null, null, null, "OPTIONS", null, null, null, "ConnectException", null);
        when(analysis.analyze(anyString(), any())).thenReturn(new TargetAnalysisService.Analysis(false,
                new AnalyzeResponse(down, null, null, null, null)));
        mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://h/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.sanity.reachable").value(false))
                .andExpect(jsonPath("$.type_detection").doesNotExist());
    }

    @Test
    void missingUrlAndBlockedTargets() throws Exception {
        mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("url is required"));
        when(analysis.sanityCheck(anyString())).thenThrow(new TargetBlockedException("'x' resolves to 10.0.0.1, a private address."));
        mvc.perform(post("/api/sanity-check").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://x/\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Target not allowed: 'x' resolves to 10.0.0.1, a private address."))
                .andExpect(jsonPath("$.blocked_target").value(true));
    }

    @Test
    void sanityCheckIs400ForMalformedUrls() throws Exception {
        when(analysis.sanityCheck("nope")).thenReturn(SanityResult.malformed());
        mvc.perform(post("/api/sanity-check").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"nope\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.valid_format").value(false))
                .andExpect(jsonPath("$.error").value("Malformed URL"));
        when(analysis.sanityCheck("http://h/")).thenReturn(REACHABLE);
        mvc.perform(post("/api/sanity-check").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://h/\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void genericErrorBodies() throws Exception {
        mvc.perform(post("/api/analyze").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("request body must be valid JSON"));
        mvc.perform(post("/api/analyze").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/nothing-here")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(get("/api/analyze")).andExpect(status().isMethodNotAllowed());
        when(analysis.sanityCheck(anyString())).thenThrow(new IllegalStateException("boom"));
        mvc.perform(post("/api/sanity-check").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"http://h/\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("internal_server_error"))
                .andExpect(jsonPath("$.detail").value("boom"));
    }

    @Test
    void corsAllowsOnlyConfiguredOrigins() throws Exception {
        mvc.perform(get("/api/health").header("Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(get("/api/health").header("Origin", "http://evil.example")).andExpect(status().isForbidden());
    }
}
