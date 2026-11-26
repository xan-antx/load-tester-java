package com.elevate.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code gate}: performance regression gate for a CI pipeline (port of the Python
 * {@code scripts/regression_gate.py}). Starts a load test with every generated case through
 * the backend's API, waits for it, compares it with a baseline run and exits
 * 0 (no regressions), 1 (at least one case got worse) or 2 (error, or no baseline).
 */
@Command(name = "gate", mixinStandardHelpOptions = true,
        description = "Fail a CI job when a load test regresses against its baseline.")
public class GateCommand implements Callable<Integer> {

    /** No regressions. */
    public static final int EXIT_OK = 0;
    /** At least one case got worse than the baseline. */
    public static final int EXIT_REGRESSIONS = 1;
    /** Bad arguments, backend unreachable, run did not complete, no baseline. */
    public static final int EXIT_ERROR = 2;

    private static final Set<String> TERMINAL = Set.of("completed", "failed", "timeout", "cancelled", "interrupted");

    @Option(names = "--api", required = true, description = "backend base URL, e.g. http://localhost:8080")
    String api;
    @Option(names = "--url", required = true, description = "target API endpoint to load-test")
    String url;
    @Option(names = "--sample", required = true, description = "path to a JSON file with a sample request body")
    Path sample;
    @Option(names = "--users", defaultValue = "50")
    int users;
    @Option(names = "--duration", defaultValue = "30", description = "seconds")
    int duration;
    @Option(names = "--spawn-rate", defaultValue = "10", description = "new users per second")
    int spawnRate;
    @Option(names = "--baseline-id", description = "compare with this run instead of the target's stored baseline")
    String baselineId;
    @Option(names = "--i-own-target", description = "confirm you own the target or have permission to load-test it (required)")
    boolean ownTarget;
    @Option(names = "--header", description = "request header 'Name: value' (repeatable)")
    List<String> headers = new ArrayList<>();
    @Option(names = "--poll-interval", defaultValue = "2.0", description = "seconds between status checks")
    double pollInterval;
    @Option(names = "--timeout", description = "give up after this many seconds (default: duration + 300)")
    Integer timeout;

    private final PrintStream out;
    private final PrintStream err;
    private final CliRunner.Sleeper sleeper;
    private BackendClient backend;

    /** For picocli: the console and real sleeping. */
    public GateCommand() {
        this(System.out, System.err, d -> Thread.sleep(d.toMillis()), null);
    }

    /**
     * @param out     progress and verdict
     * @param err     errors
     * @param sleeper waits between polls
     * @param backend the REST API, or {@code null} to talk to {@code --api}
     */
    public GateCommand(PrintStream out, PrintStream err, CliRunner.Sleeper sleeper, BackendClient backend) {
        this.out = out;
        this.err = err;
        this.sleeper = sleeper;
        this.backend = backend;
    }

    @Override
    public Integer call() {
        try {
            return runGate();
        } catch (CliException e) {
            err.println("ERROR: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    private int runGate() {
        checkRange("--users", users, 1, 1000);
        checkRange("--duration", duration, 1, 300);
        checkRange("--spawn-rate", spawnRate, 1, 20);
        if (!ownTarget) {
            throw new CliException("pass --i-own-target to confirm you own the target or may load-test it");
        }
        JsonNode sampleBody = loadSample(sample);
        if (backend == null) {
            backend = new BackendClient(api, HttpClient.newHttpClient(), JsonMapper.builder().build());
        }
        if (baselineId != null) {
            backend.get("/api/load-test-status/" + encode(baselineId));
        }
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode generate = nodes.objectNode();
        generate.set("sample_input", sampleBody);
        JsonNode cases = backend.post("/api/generate-edge-cases", generate).path("cases");
        ObjectNode confirm = nodes.objectNode();
        confirm.set("sample_input", sampleBody);
        ArrayNode labels = confirm.putArray("selected_labels");
        cases.forEach(c -> labels.add(c.path("label").asString()));
        JsonNode confirmed = backend.post("/api/confirm-selection", confirm).path("confirmed_cases");

        ObjectNode body = nodes.objectNode();
        body.put("url", url).put("users", users).put("spawn_rate", spawnRate).put("duration_seconds", duration)
                .put("i_own_target", true);
        body.set("confirmed_cases", confirmed);
        if (!headers.isEmpty()) {
            ObjectNode headerNode = body.putObject("headers");
            headers.forEach(h -> {
                int colon = h.indexOf(':');
                if (colon <= 0 || h.substring(0, colon).isBlank()) {
                    throw new CliException("header must look like 'Name: value', got '" + h + "'");
                }
                headerNode.put(h.substring(0, colon).strip(), h.substring(colon + 1).strip());
            });
        }
        String runId = backend.post("/api/start-load-test", body).path("job_id").asString();
        out.println("Started run " + runId + ": " + users + " users for " + duration + " s against " + url);

        String status = poll(runId);
        if (!"completed".equals(status)) {
            throw new CliException("run " + runId + " did not complete (status: " + (status == null ? "unknown" : status) + ")");
        }
        String query = baselineId == null ? "" : "?baseline_id=" + encode(baselineId);
        Decision decision = decide(backend.get("/api/regressions/" + runId + query));
        decision.lines().forEach(out::println);
        return decision.exitCode();
    }

    private String poll(String runId) {
        int limit = timeout != null ? timeout : duration + 300;
        int polls = Math.max(1, (int) Math.ceil(limit / pollInterval));
        String status = null;
        for (int i = 0; i < polls; i++) {
            status = backend.get("/api/load-test-status/" + runId).path("status").asString(null);
            if (status != null && TERMINAL.contains(status)) {
                break;
            }
            try {
                sleeper.sleep(Duration.ofMillis((long) (pollInterval * 1000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CliException("interrupted while waiting for run " + runId);
            }
        }
        return status;
    }

    /**
     * Turns a {@code /api/regressions} response into the exit code and summary lines. Pure.
     *
     * @param info the response
     * @return the decision
     */
    public static Decision decide(JsonNode info) {
        JsonNode base = info.get("baseline");
        if (base == null || base.isNull()) {
            return new Decision(EXIT_ERROR, List.of("No baseline to compare with. Mark a run of this target as the "
                    + "baseline in the app, or pass --baseline-id."));
        }
        List<String> lines = new ArrayList<>();
        lines.add("Baseline: run " + base.path("id").asString() + " (" + base.path("users").asInt() + " users)");
        JsonNode change = info.get("p95_change_pct");
        if (change != null && change.isNumber()) {
            lines.add(String.format(Locale.ROOT, "p95 response: %.0f ms -> %.0f ms (%+.1f%%)",
                    info.path("baseline_p95").asDouble(), info.path("current_p95").asDouble(), change.asDouble()));
        }
        JsonNode regressions = info.path("regressions");
        String threshold = info.path("threshold").asString();
        if (regressions.isEmpty()) {
            lines.add("PASS: no case's failure rate rose by " + threshold + " points or more.");
            return new Decision(EXIT_OK, lines);
        }
        lines.add("FAIL: " + regressions.size() + " case(s) got worse by " + threshold + " points or more:");
        regressions.forEach(r -> lines.add("  " + r.path("case").asString() + ": " + r.path("baseline_rate").asString()
                + "% -> " + r.path("current_rate").asString() + "% (+" + r.path("increase").asString() + " pts)"));
        return new Decision(EXIT_REGRESSIONS, lines);
    }

    private static JsonNode loadSample(Path path) {
        try {
            JsonNode node = JsonMapper.builder().build().readTree(Files.readString(path));
            if (!node.isObject()) {
                throw new CliException("sample file must contain a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw new CliException("cannot read sample file: " + e.getMessage());
        } catch (JacksonException e) {
            throw new CliException("sample file is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static void checkRange(String name, int value, int low, int high) {
        if (value < low || value > high) {
            throw new CliException(name + " must be between " + low + " and " + high);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * @param exitCode 0, 1 or 2
     * @param lines    the summary to print
     */
    public record Decision(int exitCode, List<String> lines) {
    }
}
