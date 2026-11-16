package com.elevate.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One CLI run, through the backend's HTTP API only: health → analyze → (API targets:
 * generate and confirm cases) → start → poll until the run ends → exit code.
 *
 * <p>Exit codes: 0 the SLO passed (capacity mode: at least the required users were
 * sustained), 1 it failed, 2 error (bad config, backend unreachable, run did not complete).
 */
public final class CliRunner {

    /** SLO passed. */
    public static final int EXIT_PASS = 0;
    /** SLO failed. */
    public static final int EXIT_FAIL = 1;
    /** Something went wrong. */
    public static final int EXIT_ERROR = 2;

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    private static final int EXTRA_WAIT_SECONDS = 300;
    private static final Set<String> TERMINAL = Set.of("completed", "failed", "timeout", "cancelled", "interrupted");

    /** Pauses between polls (replaceable in tests). */
    @FunctionalInterface
    public interface Sleeper {
        /** Sleeps for {@code duration}. */
        void sleep(Duration duration) throws InterruptedException;
    }

    private final BackendClient backend;
    private final PrintStream out;
    private final Sleeper sleeper;

    /**
     * @param backend the REST API
     * @param out     where progress is printed
     * @param sleeper waits between polls
     */
    public CliRunner(BackendClient backend, PrintStream out, Sleeper sleeper) {
        this.backend = backend;
        this.out = out;
        this.sleeper = sleeper;
    }

    /**
     * Runs the test and waits for it.
     *
     * @param cfg the settings
     * @return the run's final status JSON and the exit code
     * @throws CliException on any error
     */
    public Result run(CliConfig cfg) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        backend.get("/api/health");
        ObjectNode analyzeBody = nodes.objectNode().put("url", cfg.targetUrl());
        analyzeBody.set("headers", headersNode(cfg.headers()));
        JsonNode analysis = backend.post("/api/analyze", analyzeBody);
        String kind = analysis.path("type_detection").path("classification").asString("unknown");
        out.println("Target " + cfg.targetUrl() + " looks like: " + kind);

        ObjectNode start = startBody(cfg);
        JsonNode started;
        if ("website".equals(kind)) {
            JsonNode raw = analysis.path("sitemap").get("raw");
            if (raw != null && raw.isString()) {
                start.put("sitemap_raw", raw.stringValue());
            }
            started = backend.post("/api/start-website-load-test", start);
        } else {
            if (cfg.sample() == null || !cfg.sample().isObject()) {
                throw new CliException("an API target needs a sample JSON object (sample: in the config or --sample)");
            }
            ObjectNode generateBody = nodes.objectNode();
            generateBody.set("sample_input", cfg.sample());
            JsonNode generated = backend.post("/api/generate-edge-cases", generateBody).path("cases");
            ObjectNode confirmBody = nodes.objectNode();
            confirmBody.set("sample_input", cfg.sample());
            ArrayNode labels = confirmBody.putArray("selected_labels");
            selectLabels(generated, cfg).forEach(labels::add);
            ObjectNode expectations = confirmBody.putObject("expectations");
            cfg.expect().forEach(expectations::put);
            start.set("confirmed_cases", backend.post("/api/confirm-selection", confirmBody).path("confirmed_cases"));
            started = backend.post("/api/start-load-test", start);
        }
        String runId = started.path("job_id").asString();
        out.println("Started run " + runId + " (" + (cfg.capacityMode() ? "capacity" : "fixed") + " mode, "
                + start.path("confirmed_cases").size() + " cases)");
        JsonNode status = poll(runId, cfg.timeoutSeconds() != null ? cfg.timeoutSeconds()
                : cfg.expectedSeconds() + EXTRA_WAIT_SECONDS);
        return new Result(decide(cfg, status), runId, status);
    }

    /**
     * The exit code for a finished run.
     *
     * @param cfg    the settings
     * @param status the final status JSON
     * @return 0, 1 or 2
     */
    public static int decide(CliConfig cfg, JsonNode status) {
        if (!"completed".equals(status.path("status").asString())) {
            return EXIT_ERROR;
        }
        if (cfg.capacityMode()) {
            JsonNode held = status.path("capacity").get("max_sustainable_users");
            return held != null && held.isNumber() && held.asInt() >= cfg.requiredUsers() ? EXIT_PASS : EXIT_FAIL;
        }
        JsonNode verdict = status.path("slo_verdict").get("verdict");
        String text = verdict == null || verdict.isNull() ? null : verdict.asString();
        if ("FAIL".equals(text)) {
            return EXIT_FAIL;
        }
        return !cfg.slo().isEmpty() && text == null ? EXIT_FAIL : EXIT_PASS;
    }

    /** Labels to send: {@code include} (or all), minus {@code exclude}. */
    static List<String> selectLabels(JsonNode generated, CliConfig cfg) {
        List<String> all = new ArrayList<>();
        generated.forEach(c -> all.add(c.path("label").asString()));
        List<String> chosen = cfg.include() == null ? all : cfg.include();
        TreeSet<String> unknown = new TreeSet<>(chosen);
        all.forEach(unknown::remove);
        if (!unknown.isEmpty()) {
            throw new CliException("unknown case label(s) in cases.include: " + String.join(", ", unknown));
        }
        Set<String> result = new LinkedHashSet<>(chosen);
        result.removeAll(cfg.exclude());
        if (result.isEmpty()) {
            throw new CliException("no cases left to send after include/exclude");
        }
        cfg.expect().forEach((label, value) -> {
            if (!"accept".equals(value) && !"reject".equals(value)) {
                throw new CliException("cases.expect values must be accept or reject: " + label + "=" + value);
            }
        });
        return List.copyOf(result);
    }

    private static ObjectNode startBody(CliConfig cfg) {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("url", cfg.targetUrl());
        body.put("i_own_target", true);
        body.put("users", cfg.load().path("users").asInt(10));
        body.put("spawn_rate", cfg.load().path("spawn_rate").asInt(10));
        body.put("duration_seconds", cfg.load().path("duration_seconds").asInt(30));
        if (!cfg.headers().isEmpty()) {
            body.set("headers", headersNode(cfg.headers()));
        }
        if (!cfg.slo().isEmpty()) {
            body.set("slo", cfg.slo());
        }
        if (cfg.capacityMode()) {
            body.put("mode", "capacity");
            body.set("capacity", cfg.capacity());
        }
        return body;
    }

    private static ObjectNode headersNode(Map<String, String> headers) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        headers.forEach(node::put);
        return node;
    }

    private JsonNode poll(String runId, int limitSeconds) {
        long deadline = System.nanoTime() + Duration.ofSeconds(limitSeconds).toNanos();
        while (true) {
            JsonNode status = backend.get("/api/load-test-status/" + runId);
            if (TERMINAL.contains(status.path("status").asString())) {
                return status;
            }
            if (System.nanoTime() > deadline) {
                throw new CliException("run " + runId + " did not finish within " + limitSeconds + " s");
            }
            try {
                sleeper.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CliException("interrupted while waiting for run " + runId);
            }
        }
    }

    /**
     * @param exitCode 0, 1 or 2
     * @param runId    the run
     * @param status   its final status JSON
     */
    public record Result(int exitCode, String runId, JsonNode status) {
    }
}
