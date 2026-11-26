package com.elevate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The regression gate (port of test_regression_gate.py) and the sitemap check, with a mocked backend. */
class GateCommandTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void decideWithoutBaselineIsAnError() {
        GateCommand.Decision d = GateCommand.decide(json("{\"baseline\":null}"));
        assertThat(d.exitCode()).isEqualTo(2);
        assertThat(d.lines().getFirst()).startsWith("No baseline to compare with.");
    }

    @Test
    void decidePassAndFail() {
        GateCommand.Decision pass = GateCommand.decide(json("{\"baseline\":{\"id\":\"b\",\"users\":50},"
                + "\"baseline_p95\":150.2,\"current_p95\":160.7,\"p95_change_pct\":7.0,\"regressions\":[],\"threshold\":10.0}"));
        assertThat(pass.exitCode()).isZero();
        assertThat(pass.lines()).containsExactly("Baseline: run b (50 users)", "p95 response: 150 ms -> 161 ms (+7.0%)",
                "PASS: no case's failure rate rose by 10.0 points or more.");
        GateCommand.Decision fail = GateCommand.decide(json("{\"baseline\":{\"id\":\"b\",\"users\":50},"
                + "\"regressions\":[{\"case\":\"login\",\"baseline_rate\":0.0,\"current_rate\":30.0,\"increase\":30.0}],"
                + "\"threshold\":10.0}"));
        assertThat(fail.exitCode()).isEqualTo(1);
        assertThat(fail.lines()).contains("FAIL: 1 case(s) got worse by 10.0 points or more:",
                "  login: 0.0% -> 30.0% (+30.0 pts)");
    }

    private int gate(BackendClient backend, String... args) {
        GateCommand command = new GateCommand(new PrintStream(out), new PrintStream(err), d -> { }, backend);
        return new CommandLine(command).execute(args);
    }

    @Test
    void fullGateRunAgainstAMockedBackend(@TempDir Path dir) throws Exception {
        Path sample = Files.writeString(dir.resolve("s.json"), "{\"username\":\"demo\"}");
        BackendClient backend = mock(BackendClient.class);
        when(backend.post(eq("/api/generate-edge-cases"), any())).thenReturn(json("{\"cases\":[{\"label\":\"valid_sample\"}]}"));
        when(backend.post(eq("/api/confirm-selection"), any())).thenReturn(json("{\"confirmed_cases\":[]}"));
        when(backend.post(eq("/api/start-load-test"), any())).thenReturn(json("{\"job_id\":\"run1\"}"));
        when(backend.get("/api/load-test-status/base")).thenReturn(json("{\"status\":\"completed\"}"));
        when(backend.get("/api/load-test-status/run1")).thenReturn(json("{\"status\":\"running\"}"),
                json("{\"status\":\"completed\"}"));
        when(backend.get("/api/regressions/run1?baseline_id=base")).thenReturn(json(
                "{\"baseline\":{\"id\":\"base\",\"users\":5},\"regressions\":[],\"threshold\":10.0}"));
        int code = gate(backend, "--api", "http://x", "--url", "http://t/api", "--sample", sample.toString(),
                "--i-own-target", "--baseline-id", "base", "--header", "Authorization: Bearer s", "--users", "5");
        assertThat(code).isZero();
        assertThat(out.toString()).contains("Started run run1: 5 users for 30 s").contains("PASS");
        verify(backend).get("/api/load-test-status/base");
    }

    @Test
    void errorsExitWithTwo(@TempDir Path dir) throws Exception {
        Path sample = Files.writeString(dir.resolve("s.json"), "[1]");
        assertThat(gate(mock(BackendClient.class), "--api", "x", "--url", "u", "--sample", sample.toString())).isEqualTo(2);
        assertThat(err.toString()).contains("--i-own-target");
        assertThat(gate(mock(BackendClient.class), "--api", "x", "--url", "u", "--sample", sample.toString(),
                "--i-own-target")).isEqualTo(2);
        assertThat(err.toString()).contains("sample file must contain a JSON object");
        assertThat(gate(mock(BackendClient.class), "--api", "x", "--url", "u", "--sample", "s", "--users", "0",
                "--i-own-target")).isEqualTo(2);
        assertThat(err.toString()).contains("--users must be between 1 and 1000");
    }

    @Test
    void runThatDoesNotCompleteIsAnError(@TempDir Path dir) throws Exception {
        Path sample = Files.writeString(dir.resolve("s.json"), "{\"u\":1}");
        BackendClient backend = mock(BackendClient.class);
        when(backend.post(eq("/api/generate-edge-cases"), any())).thenReturn(json("{\"cases\":[]}"));
        when(backend.post(eq("/api/confirm-selection"), any())).thenReturn(json("{\"confirmed_cases\":[]}"));
        when(backend.post(eq("/api/start-load-test"), any())).thenReturn(json("{\"job_id\":\"r\"}"));
        when(backend.get("/api/load-test-status/r")).thenReturn(json("{\"status\":\"failed\"}"));
        assertThat(gate(backend, "--api", "x", "--url", "u", "--sample", sample.toString(), "--i-own-target")).isEqualTo(2);
        assertThat(err.toString()).contains("did not complete (status: failed)");
    }

    @Test
    void sitemapCheckPrintsTheExtractedPaths() {
        BackendClient backend = mock(BackendClient.class);
        String xml = "<urlset xmlns=\\\"http://www.sitemaps.org/schemas/sitemap/0.9\\\"><url><loc>https://s/about</loc></url>"
                + "<url><loc>https://s/logo.png</loc></url></urlset>";
        when(backend.post(eq("/api/analyze"), any())).thenReturn(json("{\"sitemap\":{\"found\":true,\"raw\":\"" + xml + "\"}}"));
        int code = new CommandLine(new SitemapCheckCommand(new PrintStream(out), new PrintStream(err), backend)).execute();
        assertThat(code).isZero();
        assertThat(out.toString()).contains("Sitemap found: true").contains("Extracted paths: [/about]")
                .contains("Total paths found: 1");
        when(backend.post(eq("/api/analyze"), any())).thenReturn(json("{\"sitemap\":{\"found\":false}}"));
        new CommandLine(new SitemapCheckCommand(new PrintStream(out), new PrintStream(err), backend)).execute();
        assertThat(out.toString()).contains("No raw sitemap content");
        when(backend.post(eq("/api/analyze"), any())).thenThrow(new CliException("cannot reach the backend"));
        assertThat(new CommandLine(new SitemapCheckCommand(new PrintStream(out), new PrintStream(err), backend)).execute())
                .isEqualTo(2);
        assertThat(LoadTestCli.handles("gate")).isTrue();
        assertThat(LoadTestCli.handles("worker")).isFalse();
    }
}
