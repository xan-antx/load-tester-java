package com.elevate.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.elevate.dto.CancelResponse;
import com.elevate.dto.RunView;
import com.elevate.dto.StartRunRequest;
import com.elevate.dto.StartRunResponse;
import com.elevate.service.LoadTestService;
import com.elevate.service.RunExecutor;
import com.elevate.service.RunViewService;

import jakarta.validation.Valid;

/** Starting, polling and stopping load tests. Runs execute in the background. */
@RestController
@RequestMapping("/api")
public class LoadTestController {

    private final LoadTestService loadTests;
    private final RunViewService views;
    private final RunExecutor executor;

    /**
     * @param loadTests starts runs
     * @param views     reads run status
     * @param executor  cancels runs
     */
    public LoadTestController(LoadTestService loadTests, RunViewService views, RunExecutor executor) {
        this.loadTests = loadTests;
        this.views = views;
        this.executor = executor;
    }

    /** Starts an API load test (202; poll the returned id). */
    @PostMapping("/start-load-test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartRunResponse startLoadTest(@Valid @RequestBody StartRunRequest request) {
        return loadTests.startApiTest(request);
    }

    /** Starts a website load test (202). */
    @PostMapping("/start-website-load-test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartRunResponse startWebsiteLoadTest(@Valid @RequestBody StartRunRequest request) {
        return loadTests.startWebsiteTest(request);
    }

    /** Status and results of a run, split group or series. */
    @GetMapping("/load-test-status/{id}")
    public RunView status(@PathVariable String id) {
        return views.status(id);
    }

    /** Stops a run (202); its status becomes {@code cancelled} once the engine has stopped. */
    @PostMapping("/cancel/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CancelResponse cancel(@PathVariable String id) {
        executor.cancel(id);
        return new CancelResponse(id, "cancelling");
    }
}
