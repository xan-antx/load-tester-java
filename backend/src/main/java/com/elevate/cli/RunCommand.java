package com.elevate.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code run}: one load test from a YAML config (see {@code loadtest.example.yml}); any value
 * can be overridden with a flag, e.g. {@code --users 30 --duration 20}.
 */
@Command(name = "run", mixinStandardHelpOptions = true,
        description = "Run one load test through the backend and report the SLO verdict (exit 0 pass, 1 fail, 2 error).")
public class RunCommand implements Callable<Integer> {

    @Option(names = "--config", description = "YAML config file")
    Path config;
    @Option(names = "--api", description = "backend base URL (default: $ELEVATE_API or http://localhost:8080)")
    String api;
    @Option(names = "--url", description = "target URL")
    String url;
    @Option(names = "--sample", description = "sample JSON body: a file path or inline JSON")
    String sample;
    @Option(names = "--cases", split = ",", description = "case labels to send (default: the config, or all)")
    List<String> cases;
    @Option(names = "--mode", description = "fixed or capacity")
    String mode;
    @Option(names = "--users")
    Integer users;
    @Option(names = "--spawn-rate")
    Integer spawnRate;
    @Option(names = "--duration", description = "seconds (fixed mode)")
    Integer duration;
    @Option(names = "--start-users")
    Integer startUsers;
    @Option(names = "--step-users")
    Integer stepUsers;
    @Option(names = "--step-duration", description = "seconds per step (capacity mode)")
    Integer stepDuration;
    @Option(names = "--max-users")
    Integer maxUsers;
    @Option(names = "--slo-p95-ms")
    Double sloP95;
    @Option(names = "--slo-avg-ms")
    Double sloAvg;
    @Option(names = "--slo-server-error-pct")
    Double sloServerErrors;
    @Option(names = "--slo-unexpected-pct")
    Double sloUnexpected;
    @Option(names = "--slo-min-rps")
    Double sloMinRps;
    @Option(names = "--out-dir", description = "where to write the report and JSON (default: loadtest-results)")
    String outDir;
    @Option(names = "--i-own-target", description = "confirm you own the target or may load-test it")
    Boolean ownTarget;
    @Option(names = "--header", description = "request header 'Name: value' (repeatable)")
    List<String> headers = new ArrayList<>();
    @Option(names = "--timeout", description = "give up after this many seconds (default: run length + 300)")
    Integer timeout;

    private final PrintStream out;
    private final PrintStream err;

    /** For picocli: prints to the console. */
    public RunCommand() {
        this(System.out, System.err);
    }

    /**
     * @param out progress and summary
     * @param err error messages
     */
    public RunCommand(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    @Override
    public Integer call() {
        try {
            CliConfig cfg = CliConfig.from(mergedConfig(), System.getenv("ELEVATE_API"), timeout);
            BackendClient backend = new BackendClient(cfg.api(), HttpClient.newHttpClient(), JsonMapper.builder().build());
            CliRunner.Result result = new CliRunner(backend, out, d -> Thread.sleep(d.toMillis())).run(cfg);
            String report = backend.getText("/api/report/" + result.runId());
            List<Path> files = CliOutputs.write(cfg, result, report);
            out.println(CliOutputs.consoleSummary(cfg, result));
            out.println("Report: " + files.get(0) + "\nJSON:   " + files.get(1));
            return result.exitCode();
        } catch (CliException e) {
            err.println("ERROR: " + e.getMessage());
            return CliRunner.EXIT_ERROR;
        } catch (RuntimeException e) {
            err.println("ERROR: unexpected " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return CliRunner.EXIT_ERROR;
        }
    }

    /** The YAML config with the command-line flags layered on top. */
    ObjectNode mergedConfig() {
        ObjectNode root = readConfig();
        set(root, "target", "url", url);
        set(root, "target", "i_own_target", ownTarget);
        set(root, "load", "mode", mode);
        set(root, "load", "users", users);
        set(root, "load", "spawn_rate", spawnRate);
        set(root, "load", "duration_seconds", duration);
        set(root, "capacity", "start_users", startUsers);
        set(root, "capacity", "step_users", stepUsers);
        set(root, "capacity", "step_duration_seconds", stepDuration);
        set(root, "capacity", "max_users", maxUsers);
        set(root, "slo", "p95_ms", sloP95);
        set(root, "slo", "avg_ms", sloAvg);
        set(root, "slo", "max_server_error_rate_pct", sloServerErrors);
        set(root, "slo", "max_unexpected_outcome_rate_pct", sloUnexpected);
        set(root, "slo", "min_rps", sloMinRps);
        set(root, "output", "dir", outDir);
        if (api != null) {
            root.put("api", api);
        }
        for (String header : headers) {
            int colon = header.indexOf(':');
            if (colon <= 0 || header.substring(0, colon).isBlank()) {
                throw new CliException("header must look like 'Name: value', got '" + header + "'");
            }
            section(section(root, "target"), "headers").put(header.substring(0, colon).strip(),
                    header.substring(colon + 1).strip());
        }
        if (cases != null) {
            JsonNodeFactory nodes = JsonNodeFactory.instance;
            section(root, "cases").set("include", nodes.arrayNode().addAll(cases.stream()
                    .map(String::strip).filter(s -> !s.isEmpty()).map(nodes::stringNode).toList()));
        }
        if (sample != null) {
            root.set("sample", readSample(sample));
        }
        return root;
    }

    private ObjectNode readConfig() {
        if (config == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode tree = YAMLMapper.builder().build().readTree(Files.readString(config));
            if (tree == null || tree.isNull() || tree.isMissingNode()) {
                return JsonNodeFactory.instance.objectNode();
            }
            if (!tree.isObject()) {
                throw new CliException("config must be a mapping");
            }
            return (ObjectNode) tree;
        } catch (IOException e) {
            throw new CliException("cannot read config: " + e.getMessage());
        } catch (JacksonException e) {
            throw new CliException("config is not valid YAML: " + e.getOriginalMessage());
        }
    }

    private static JsonNode readSample(String text) {
        String json = text;
        try {
            Path file = Path.of(text);
            if (Files.isRegularFile(file)) {
                json = Files.readString(file);
            }
        } catch (InvalidPathException | IOException e) {
            json = text;
        }
        try {
            return JsonMapper.builder().build().readTree(json);
        } catch (JacksonException e) {
            throw new CliException("sample is not valid JSON: " + e.getOriginalMessage());
        }
    }

    private static void set(ObjectNode root, String sectionName, String key, Object value) {
        if (value == null) {
            return;
        }
        ObjectNode section = section(root, sectionName);
        switch (value) {
            case Integer i -> section.put(key, i);
            case Double d -> section.put(key, d);
            case Boolean b -> section.put(key, b);
            default -> section.put(key, value.toString());
        }
    }

    private static ObjectNode section(ObjectNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node instanceof ObjectNode object) {
            return object;
        }
        return parent.putObject(name);
    }
}
