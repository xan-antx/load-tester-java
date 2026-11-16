package com.elevate.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the results of a CLI run into the output directory: the Markdown report (from
 * the backend), the full JSON status, and {@code summary.md} with the verdict table. Also
 * builds the short console summary.
 */
final class CliOutputs {

    private CliOutputs() {
    }

    /**
     * @param cfg     the settings
     * @param result  the finished run
     * @param report  the Markdown report
     * @return the paths written: report, JSON, summary
     */
    static List<Path> write(CliConfig cfg, CliRunner.Result result, String report) {
        String shortId = result.runId().length() > 8 ? result.runId().substring(0, 8) : result.runId();
        Path dir = cfg.outDir();
        Path reportFile = dir.resolve("report-" + shortId + ".md");
        Path jsonFile = dir.resolve("result-" + shortId + ".json");
        Path summaryFile = dir.resolve("summary.md");
        ObjectNode json = JsonNodeFactory.instance.objectNode();
        json.put("exit_code", result.exitCode());
        json.put("target_url", cfg.targetUrl());
        json.set("status", result.status());
        JsonMapper pretty = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        try {
            Files.createDirectories(dir);
            Files.writeString(reportFile, report, StandardCharsets.UTF_8);
            Files.writeString(jsonFile, pretty.writeValueAsString(json), StandardCharsets.UTF_8);
            Files.writeString(summaryFile, VerdictTable.render(cfg, result.status(), result.exitCode(), false),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliException("cannot write results to " + dir + ": " + e.getMessage());
        }
        return List.of(reportFile, jsonFile, summaryFile);
    }

    /** The readable console summary (ASCII only). */
    static String consoleSummary(CliConfig cfg, CliRunner.Result result) {
        JsonNode status = result.status();
        boolean split = status.path("child_jobs").isArray();
        JsonNode total = aggregatedRow(status.path(split ? "aggregated_stats_csv" : "stats_csv").asString(""));
        StringBuilder text = new StringBuilder("\nRun ").append(result.runId()).append(": ")
                .append(status.path("status").asString());
        if (split) {
            text.append(" (split into ").append(status.path("child_jobs").size())
                    .append(" parallel engine instances; histograms merged, p95 exact)");
        }
        text.append("\n  requests ").append(total.path("Request Count").asString("-"))
                .append(", ").append(total.path("Requests/s").asString("-")).append(" req/s, avg ")
                .append(total.path("Average Response Time").asString("-")).append(" ms, p95 ")
                .append(total.path("95%").asString("-")).append(" ms\n");
        JsonNode outcomes = status.get("outcome_summary");
        if (outcomes != null && outcomes.isObject()) {
            text.append("  handled correctly ").append(outcomes.path("handled_correctly_pct").asString())
                    .append("%, accepted invalid input ").append(outcomes.path("accepted_invalid_input").asLong())
                    .append(", rejected valid input ").append(outcomes.path("rejected_valid_input").asLong())
                    .append(", server errors ")
                    .append(outcomes.path("server_error").asLong() + outcomes.path("connection").asLong())
                    .append(", rate limited ").append(outcomes.path("rate_limited").asLong()).append('\n');
        }
        return text.append('\n').append(VerdictTable.render(cfg, status, result.exitCode(), true)).toString();
    }

    /** The "Aggregated" row of a stats CSV as {column: value}. */
    private static JsonNode aggregatedRow(String csv) {
        ObjectNode row = JsonNodeFactory.instance.objectNode();
        String[] lines = csv.strip().split("\n");
        if (lines.length < 2) {
            return row;
        }
        String[] header = lines[0].split(",");
        for (String line : lines) {
            String[] values = line.split(",");
            if (values.length > 1 && "Aggregated".equals(values[1])) {
                for (int i = 0; i < header.length && i < values.length; i++) {
                    row.put(header[i], values[i]);
                }
            }
        }
        return row;
    }
}
