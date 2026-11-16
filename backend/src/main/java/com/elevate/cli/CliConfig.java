package com.elevate.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The validated settings of one CLI run, built from the YAML config with command-line
 * flags layered on top (see {@code loadtest.example.yml}).
 *
 * @param api             backend base URL
 * @param targetUrl       the target
 * @param headers         extra request headers
 * @param sample          the sample request body (API targets)
 * @param include         case labels to send, or {@code null} for all
 * @param exclude         case labels to leave out
 * @param expect          expectation overrides {@code {label: accept|reject}}
 * @param capacityMode    {@code load.mode: capacity}
 * @param load            {@code users}, {@code spawn_rate}, {@code duration_seconds}
 * @param capacity        the capacity plan (capacity mode)
 * @param minUsers        capacity mode: users that must be sustained for exit code 0
 * @param slo             thresholds
 * @param outDir          where the report and JSON go
 * @param timeoutSeconds  give up after this long, or {@code null} for run length + 300 s
 */
public record CliConfig(String api, String targetUrl, Map<String, String> headers, JsonNode sample,
                        List<String> include, Set<String> exclude, Map<String, String> expect, boolean capacityMode,
                        ObjectNode load, ObjectNode capacity, Integer minUsers, ObjectNode slo, Path outDir,
                        Integer timeoutSeconds) {

    /** Default backend URL. */
    public static final String DEFAULT_API = "http://localhost:8080";
    private static final Set<String> SLO_KEYS = Set.of("p95_ms", "avg_ms", "max_server_error_rate_pct",
            "max_unexpected_outcome_rate_pct", "min_rps");
    private static final Set<String> PLAN_KEYS = Set.of("start_users", "step_users", "step_duration_seconds",
            "max_users", "early_stop");

    /**
     * @param root    the merged config tree
     * @param envApi  {@code ELEVATE_API}, or {@code null}
     * @param timeout {@code --timeout}, or {@code null}
     * @return the validated config
     * @throws CliException when something required is missing or wrong
     */
    public static CliConfig from(ObjectNode root, String envApi, Integer timeout) {
        ObjectNode target = section(root, "target");
        ObjectNode load = section(root, "load");
        ObjectNode slo = section(root, "slo");
        ObjectNode cases = section(root, "cases");
        TreeSet<String> unknown = new TreeSet<>(slo.propertyNames());
        unknown.removeAll(SLO_KEYS);
        if (!unknown.isEmpty()) {
            throw new CliException("unknown SLO threshold(s): " + String.join(", ", unknown));
        }
        String url = text(target, "url");
        if (url == null || url.isBlank()) {
            throw new CliException("no target URL: set target.url in the config or pass --url");
        }
        JsonNode own = target.get("i_own_target");
        if (own == null || !own.isBoolean() || !own.booleanValue()) {
            throw new CliException("confirm you own the target or may load-test it: i_own_target: true or --i-own-target");
        }
        String mode = load.has("mode") ? text(load, "mode") : "fixed";
        if (!"fixed".equals(mode) && !"capacity".equals(mode)) {
            throw new CliException("load.mode must be \"fixed\" or \"capacity\"");
        }
        ObjectNode capacity = section(root, "capacity");
        ObjectNode plan = JsonNodeFactory.instance.objectNode();
        capacity.properties().stream().filter(e -> PLAN_KEYS.contains(e.getKey()))
                .forEach(e -> plan.set(e.getKey(), e.getValue()));
        String api = text(root, "api");
        api = api != null ? api : envApi != null ? envApi : DEFAULT_API;
        String outDir = text(section(root, "output"), "dir");
        return new CliConfig(api, url, stringMap(section(target, "headers")), root.get("sample"),
                include(cases.get("include")), new TreeSet<>(strings(cases.get("exclude"))),
                stringMap(section(cases, "expect")), "capacity".equals(mode), load, plan,
                capacity.has("min_users") ? capacity.get("min_users").asInt() : null, withoutNulls(slo),
                Path.of(outDir == null ? "loadtest-results" : outDir), timeout);
    }

    /** Expected length of the run in seconds (for the timeout). */
    public int expectedSeconds() {
        if (capacityMode) {
            int start = capacity.path("start_users").asInt(1);
            int step = Math.max(capacity.path("step_users").asInt(1), 1);
            int max = capacity.path("max_users").asInt(start);
            return Math.max((max - start) / step + 1, 1) * capacity.path("step_duration_seconds").asInt(10);
        }
        return load.path("duration_seconds").asInt(30);
    }

    /** Users a capacity run must sustain for exit code 0. */
    public int requiredUsers() {
        return minUsers != null ? minUsers : capacity.path("start_users").asInt(1);
    }

    private static ObjectNode section(ObjectNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) {
            ObjectNode empty = JsonNodeFactory.instance.objectNode();
            parent.set(name, empty);
            return empty;
        }
        if (!node.isObject()) {
            throw new CliException(name + " must be a mapping");
        }
        return (ObjectNode) node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static List<String> include(JsonNode node) {
        if (node == null || node.isNull() || (node.isString() && "all".equals(node.stringValue()))) {
            return null;
        }
        return strings(node);
    }

    private static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> values.add(n.asString()));
        }
        return values;
    }

    private static Map<String, String> stringMap(ObjectNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        node.properties().forEach(e -> map.put(e.getKey(), e.getValue().asString()));
        return map;
    }

    private static ObjectNode withoutNulls(ObjectNode node) {
        ObjectNode copy = JsonNodeFactory.instance.objectNode();
        node.properties().stream().filter(e -> !e.getValue().isNull()).forEach(e -> copy.set(e.getKey(), e.getValue()));
        return copy;
    }
}
