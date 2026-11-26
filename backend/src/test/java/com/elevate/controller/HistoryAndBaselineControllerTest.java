package com.elevate.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.elevate.analysis.compare.CaseComparison;
import com.elevate.analysis.compare.RegressionReport;
import com.elevate.dto.BaselineLookupResponse;
import com.elevate.dto.BaselineResponse;
import com.elevate.dto.CompareResponse;
import com.elevate.dto.HistoryResponse;
import com.elevate.dto.PairCompareResponse;
import com.elevate.dto.RegressionsResponse;
import com.elevate.exception.BadRequestException;
import com.elevate.exception.NotFoundException;
import com.elevate.model.RunMode;
import com.elevate.model.RunStatus;
import com.elevate.service.BaselineService;
import com.elevate.service.ReportService;
import com.elevate.service.RunViewService;

@WebMvcTest({HistoryController.class, BaselineController.class, ReportController.class})
class HistoryAndBaselineControllerTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    RunViewService views;
    @MockitoBean
    BaselineService baselines;
    @MockitoBean
    ReportService reports;

    @Test
    void historyRowsHaveEitherJobIdOrGroupId() throws Exception {
        HistoryResponse.Row job = new HistoryResponse.Row("j", null, "http://h", 5, RunStatus.COMPLETED, "t", "t", false, null,
                RunMode.FIXED, "FAIL");
        HistoryResponse.Row group = new HistoryResponse.Row(null, "g", "http://h", 120, RunStatus.RUNNING, "t", null, true, null,
                RunMode.FIXED, null);
        when(views.history()).thenReturn(new HistoryResponse(List.of(job), List.of(group)));
        mvc.perform(get("/api/jobs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].job_id").value("j"))
                .andExpect(jsonPath("$.jobs[0].group_id").doesNotExist())
                .andExpect(jsonPath("$.jobs[0].slo_verdict").value("FAIL"))
                .andExpect(jsonPath("$.job_groups[0].group_id").value("g"))
                .andExpect(jsonPath("$.job_groups[0].is_baseline").value(true));
    }

    @Test
    void compareBothForms() throws Exception {
        when(views.compare(any())).thenReturn(new CompareResponse(List.of(), new CaseComparison(5, 10.0, List.of(
                new CaseComparison.Row("login", List.of(new CaseComparison.Cell(10, 3, 30.0, false)), 30.0, true)))));
        mvc.perform(post("/api/compare-jobs").contentType(MediaType.APPLICATION_JSON).content("{\"job_ids\":[\"a\",\"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case_comparison.min_requests").value(5))
                .andExpect(jsonPath("$.case_comparison.rows[0].case").value("login"))
                .andExpect(jsonPath("$.case_comparison.rows[0].cells[0].low_sample").value(false));
        when(views.comparePair("a", "b")).thenReturn(new PairCompareResponse(null, null));
        mvc.perform(post("/api/compare-jobs").contentType(MediaType.APPLICATION_JSON).content("{\"job_id_a\":\"a\",\"job_id_b\":\"b\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.job_a").isEmpty());
        when(views.comparePair(isNull(), isNull())).thenThrow(new BadRequestException("job_id_a and job_id_b are required"));
        mvc.perform(post("/api/compare-jobs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("job_id_a and job_id_b are required"));
    }

    @Test
    void baselineEndpoints() throws Exception {
        when(baselines.set(any())).thenReturn(new BaselineResponse("r", "http://h"));
        mvc.perform(post("/api/baseline").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"r\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.baseline_id").value("r"))
                .andExpect(jsonPath("$.target_url").value("http://h"));
        when(baselines.lookup("http://h")).thenReturn(new BaselineLookupResponse("http://h", null));
        mvc.perform(get("/api/baseline").param("target_url", "http://h")).andExpect(status().isOk())
                .andExpect(jsonPath("$.baseline_id").isEmpty());
        when(baselines.lookup(null)).thenThrow(new BadRequestException("target_url query parameter is required"));
        mvc.perform(get("/api/baseline")).andExpect(status().isBadRequest());
    }

    @Test
    void regressionsAreUnwrappedIntoTheResponse() throws Exception {
        when(baselines.regressions(eq("r"), isNull())).thenReturn(new RegressionsResponse("http://h", "b", false,
                new RegressionsResponse.Baseline("b", 15, "t", false),
                new RegressionReport(List.of(new RegressionReport.Regression("login", 0, 30, 30, 20, 20)), 150.0, 180.0, 20.0,
                        10.0, 5), true));
        mvc.perform(get("/api/regressions/r")).andExpect(status().isOk())
                .andExpect(jsonPath("$.is_current_baseline").value(false))
                .andExpect(jsonPath("$.baseline.users").value(15))
                .andExpect(jsonPath("$.regressions[0].case").value("login"))
                .andExpect(jsonPath("$.p95_change_pct").value(20.0))
                .andExpect(jsonPath("$.p95_exact").value(true))
                .andExpect(jsonPath("$.report").doesNotExist());
        when(baselines.regressions(eq("x"), eq("nope"))).thenThrow(new NotFoundException("baseline run 'nope' not found"));
        mvc.perform(get("/api/regressions/x").param("baseline_id", "nope")).andExpect(status().isNotFound());
    }

    @Test
    void reportIsMarkdown() throws Exception {
        when(reports.markdown("r")).thenReturn("# Load test report");
        mvc.perform(get("/api/report/r")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string("# Load test report"));
    }
}
