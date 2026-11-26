package com.elevate.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.elevate.analysis.slo.Slo;
import com.elevate.analysis.slo.SloThreshold;
import com.elevate.analysis.slo.SloVerdict;
import com.elevate.dto.ChildJobView;
import com.elevate.dto.GroupView;
import com.elevate.dto.JobView;
import com.elevate.dto.RunAnalysis;
import com.elevate.dto.SeriesView;
import com.elevate.dto.StartRunResponse;
import com.elevate.exception.NotFoundException;
import com.elevate.exception.OwnershipNotConfirmedException;
import com.elevate.exception.TooManyActiveRunsException;
import com.elevate.model.CapacityPlan;
import com.elevate.model.Expectation;
import com.elevate.model.JobType;
import com.elevate.model.RunMode;
import com.elevate.model.RunStatus;
import com.elevate.service.LoadTestService;
import com.elevate.service.RunExecutor;
import com.elevate.service.RunViewService;

@WebMvcTest(LoadTestController.class)
class LoadTestControllerTest {

    @Autowired
    MockMvc mvc;
    @MockitoBean
    LoadTestService loadTests;
    @MockitoBean
    RunViewService views;
    @MockitoBean
    RunExecutor executor;

    private static final String START = "{\"url\":\"http://127.0.0.1:8000/api/login\",\"i_own_target\":true,"
            + "\"confirmed_cases\":[{\"label\":\"valid_sample\",\"payload\":{}}]}";
    private static final RunAnalysis ANALYSIS = new RunAnalysis(List.of(), 0, 0.0, null,
            new SloVerdict("PASS", List.of(), 1, 0, 0), null);

    private org.springframework.test.web.servlet.ResultActions start(String body) throws Exception {
        return mvc.perform(post("/api/start-load-test").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void startReturns202WithTheJobId() throws Exception {
        when(loadTests.startApiTest(any())).thenReturn(StartRunResponse.run("abc", false, null));
        start(START).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job_id").value("abc"))
                .andExpect(jsonPath("$.status").value("started"))
                .andExpect(jsonPath("$.is_group").value(false))
                .andExpect(jsonPath("$.is_series").doesNotExist());
    }

    @Test
    void seriesAndCapacityResponses() throws Exception {
        when(loadTests.startApiTest(any())).thenReturn(StartRunResponse.series("s", true, 3, null));
        start(START).andExpect(jsonPath("$.series_id").value("s")).andExpect(jsonPath("$.is_series").value(true))
                .andExpect(jsonPath("$.repeat").value(3));
        when(loadTests.startApiTest(any())).thenReturn(StartRunResponse.capacity("c", new CapacityPlan(20, 20, 12, 60, true)));
        start(START).andExpect(jsonPath("$.mode").value("capacity"))
                .andExpect(jsonPath("$.capacity_plan.steps[2]").value(60))
                .andExpect(jsonPath("$.capacity_plan.total_seconds").value(36))
                .andExpect(jsonPath("$.capacity_plan.early_stop").value(true));
        when(loadTests.startWebsiteTest(any())).thenReturn(StartRunResponse.run("w", false, List.of("/", "/about")));
        mvc.perform(post("/api/start-website-load-test").contentType(MediaType.APPLICATION_JSON).content(START))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.paths_used[1]").value("/about"));
    }

    @Test
    void beanValidationChecksTheLimits() throws Exception {
        start("{\"url\":\"http://h/\",\"users\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("users must be between 1 and 1000"));
        start("{\"url\":\"http://h/\",\"spawn_rate\":21}").andExpect(jsonPath("$.error").value("spawn_rate must be between 1 and 20"));
        start("{\"url\":\"http://h/\",\"duration_seconds\":301}")
                .andExpect(jsonPath("$.error").value("duration_seconds must be between 1 and 300"));
        start("{\"url\":\"http://h/\",\"repeat\":6}").andExpect(jsonPath("$.error").value("repeat must be between 1 and 5"));
        start("{\"users\":5}").andExpect(jsonPath("$.error").value("url (string) is required"));
        start("{\"url\":\"http://h/\",\"users\":\"many\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("users has the wrong type"));
        verifyNoInteractions(loadTests);
    }

    @Test
    void serviceErrorsKeepTheirExtraFields() throws Exception {
        when(loadTests.startApiTest(any())).thenThrow(new OwnershipNotConfirmedException(), new TooManyActiveRunsException(1, 1));
        start(START).andExpect(status().isBadRequest()).andExpect(jsonPath("$.missing_confirmation").value("i_own_target"));
        start(START).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.busy").value(true))
                .andExpect(jsonPath("$.active_runs").value(1))
                .andExpect(jsonPath("$.max_active_runs").value(1))
                .andExpect(jsonPath("$.error").value("A load test is already running, and this server runs at most 1 at a time. "
                        + "Wait for it to finish (or stop it), then start again."));
    }

    @Test
    void statusOfASingleJob() throws Exception {
        Slo slo = new Slo(Map.of(SloThreshold.P95_MS, 500.0));
        when(views.status("j")).thenReturn(new JobView("j", "http://h", JobType.API, 5, 1, 30, RunStatus.COMPLETED, null, null,
                "2026-11-16 10:00:00", "2026-11-16 10:00:30", true, List.of("Authorization"), "Type,Name\n", null, null,
                Map.of("valid_sample", Expectation.ACCEPT), slo, RunMode.FIXED, null, ANALYSIS));
        mvc.perform(get("/api/load-test-status/j")).andExpect(status().isOk())
                .andExpect(jsonPath("$.job_id").value("j"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.is_baseline").value(true))
                .andExpect(jsonPath("$.job_type").value("api"))
                .andExpect(jsonPath("$.case_expectations.valid_sample").value("accept"))
                .andExpect(jsonPath("$.slo.p95_ms").value(500.0))
                .andExpect(jsonPath("$.slo_verdict.verdict").value("PASS"))
                .andExpect(jsonPath("$.problem_rate").value(0.0))
                .andExpect(jsonPath("$.failures_csv").isEmpty())
                .andExpect(jsonPath("$.child_jobs").doesNotExist());
    }

    @Test
    void statusOfAGroupAndASeries() throws Exception {
        when(views.status("g")).thenReturn(new GroupView("g", "http://h", JobType.API, 120, 20, 10, 3, RunStatus.RUNNING, null,
                "t", null, false, null, null, null, null, null, null, RunMode.FIXED, null,
                List.of(new ChildJobView("c1", "http://h", 40, 20, 10, RunStatus.RUNNING, null, null, null, null, "g", "t",
                        null, false, null, null, null, RunMode.FIXED, null)), true, ANALYSIS));
        mvc.perform(get("/api/load-test-status/g")).andExpect(jsonPath("$.group_id").value("g"))
                .andExpect(jsonPath("$.child_jobs[0].job_id").value("c1"))
                .andExpect(jsonPath("$.child_jobs[0].group_id").value("g"))
                .andExpect(jsonPath("$.capacity_plan").isEmpty())
                .andExpect(jsonPath("$.header_names").isEmpty())
                .andExpect(jsonPath("$.p95_exact").value(true));
        when(views.status("s")).thenReturn(new SeriesView("s", "http://h", JobType.API, 5, 1, 10, 2, List.of("r1"),
                RunStatus.RUNNING, null, List.of(), "t", null,
                List.of(new SeriesView.Repeat("r1", RunStatus.COMPLETED, false, "PASS", 0.0)), null, true));
        mvc.perform(get("/api/load-test-status/s")).andExpect(jsonPath("$.series_id").value("s"))
                .andExpect(jsonPath("$.runs[0].slo_verdict").value("PASS"))
                .andExpect(jsonPath("$.runs[0].is_group").value(false))
                .andExpect(jsonPath("$.repeat_count").value(2));
        when(views.status("none")).thenThrow(new NotFoundException("job_id not found"));
        mvc.perform(get("/api/load-test-status/none")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("job_id not found"));
    }

    @Test
    void cancel() throws Exception {
        mvc.perform(post("/api/cancel/x")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("x")).andExpect(jsonPath("$.status").value("cancelling"));
        doThrow(new NotFoundException("run 'y' not found")).when(executor).cancel("y");
        mvc.perform(post("/api/cancel/y")).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("run 'y' not found"));
    }
}
