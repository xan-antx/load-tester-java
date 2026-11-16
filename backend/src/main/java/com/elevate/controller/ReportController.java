package com.elevate.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.elevate.service.ReportService;

/** The Markdown report of a run (a Java addition used by the CLI and CI). */
@RestController
@RequestMapping("/api")
public class ReportController {

    private final ReportService reports;

    /** @param reports writes reports */
    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    /** The report as {@code text/markdown}. */
    @GetMapping(value = "/report/{id}", produces = "text/markdown;charset=UTF-8")
    public String report(@PathVariable String id) {
        return reports.markdown(id);
    }
}
