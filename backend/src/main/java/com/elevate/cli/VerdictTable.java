package com.elevate.cli;

import java.math.BigDecimal;
import java.math.RoundingMode;

import tools.jackson.databind.JsonNode;

/**
 * The verdict line and its table, in Markdown, for {@code summary.md} (which CI appends to
 * the job summary) and, in plain ASCII, for the console.
 */
final class VerdictTable {

    private VerdictTable() {
    }

    /**
     * @param cfg      the settings
     * @param status   the final status JSON
     * @param exitCode the CLI's exit code
     * @param plain    ASCII only (for consoles that cannot print ≤ or emoji)
     * @return the table
     */
    static String render(CliConfig cfg, JsonNode status, int exitCode, boolean plain) {
        String word = switch (exitCode) {
            case CliRunner.EXIT_PASS -> "PASS";
            case CliRunner.EXIT_FAIL -> "FAIL";
            default -> "ERROR";
        };
        StringBuilder md = new StringBuilder();
        if (cfg.capacityMode()) {
            JsonNode capacity = status.path("capacity");
            md.append("### Capacity: ").append(word).append("\n\n**")
                    .append(capacity.path("headline").asString("no result")).append("**\n\n");
            if (capacity.path("breaking_point_users").isNumber()) {
                md.append("Breaking point: ").append(capacity.path("breaking_point_users").asInt()).append(" users (")
                        .append(capacity.path("breaking_reason").asString()).append(").\n\n");
            }
            md.append("| Users | Req/s | p95 ms | Server errors % | Verdict |\n|---|---|---|---|---|\n");
            for (JsonNode s : capacity.path("steps")) {
                md.append("| ").append(s.path("planned_users").asInt()).append(" | ").append(number(s.get("rps"), 1))
                        .append(" | ").append(number(s.get("p95_ms"), 0)).append(" | ")
                        .append(number(s.get("server_error_rate_pct"), 1)).append(" | ")
                        .append(s.path("complete").asBoolean() ? s.path("verdict").asString("-") : "partial")
                        .append(" |\n");
            }
            return md.toString();
        }
        md.append("### SLO verdict: ").append(word).append("\n\n");
        JsonNode verdict = status.get("slo_verdict");
        if (verdict == null || verdict.isNull()) {
            md.append(cfg.slo().isEmpty() ? "No SLO was set." : "The run did not complete, so no verdict.").append('\n');
            return md.toString();
        }
        md.append("| Threshold | Target | Actual | Result |\n|---|---|---|---|\n");
        for (JsonNode c : verdict.path("checks")) {
            boolean max = "<=".equals(c.path("comparison").asString());
            String sign = plain ? c.path("comparison").asString() : max ? "≤" : "≥";
            JsonNode passed = c.get("passed");
            String result = passed == null || passed.isNull() ? "not measured"
                    : passed.asBoolean() ? (plain ? "met" : "✅ met") : (plain ? "MISSED" : "❌ missed");
            String unit = c.path("unit").asString();
            JsonNode actual = c.get("actual");
            md.append("| ").append(c.path("label").asString()).append(" | ").append(sign).append(' ')
                    .append(number(c.get("target"), 2)).append(' ').append(unit).append(" | ")
                    .append(actual == null || actual.isNull() ? "-" : number(actual, 2) + " " + unit)
                    .append(" | ").append(result).append(" |\n");
        }
        return md.toString();
    }

    /** A number with at most {@code digits} decimals, or {@code -}. */
    static String number(JsonNode value, int digits) {
        if (value == null || !value.isNumber()) {
            return "-";
        }
        return BigDecimal.valueOf(value.doubleValue())
                .setScale(digits, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
