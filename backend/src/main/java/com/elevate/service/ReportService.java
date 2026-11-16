package com.elevate.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.elevate.analysis.capacity.CapacityResult;
import com.elevate.analysis.capacity.CapacityStep;
import com.elevate.analysis.outcome.FailureGroup;
import com.elevate.analysis.outcome.OutcomeSummary;
import com.elevate.analysis.slo.SloVerdict;
import com.elevate.analysis.stats.CaseStats;
import com.elevate.analysis.stats.Numbers;
import com.elevate.analysis.stats.StatsTable;
import com.elevate.dto.Timestamps;
import com.elevate.exception.NotFoundException;
import com.elevate.model.Expectation;
import com.elevate.model.MeasuredRun;
import com.elevate.model.SplitGroup;

/** Writes the Markdown report of a run (used by the CLI and the CI performance gate). */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private static final String DASH = "—";

    private final RunSnapshots snapshots;

    /** @param snapshots loads runs with their results */
    public ReportService(RunSnapshots snapshots) {
        this.snapshots = snapshots;
    }

    /**
     * @param id a single run or split group
     * @return the report
     * @throws NotFoundException when no such run exists
     */
    public String markdown(String id) {
        RunSnapshot s = snapshots.findSummary(id).orElseThrow(() -> new NotFoundException("run '" + id + "' not found"));
        MeasuredRun run = s.run();
        StringBuilder md = new StringBuilder();
        md.append("# Load test report — Elevate Load Tester\n\n")
                .append("- Target: ").append(run.getTargetUrl()).append('\n')
                .append("- Run id: ").append(run.getId()).append('\n')
                .append("- Type: ").append(run.getJobType().wire()).append(", mode: ").append(run.getMode().wire()).append('\n')
                .append("- Status: ").append(s.status().wire()).append('\n')
                .append("- Started: ").append(Timestamps.format(run.getCreatedAt())).append(" UTC\n")
                .append("- Load: ").append(run.getUsers()).append(" users, spawn rate ").append(run.getSpawnRate())
                .append("/s, ").append(run.getDurationSeconds()).append(" s\n")
                .append(run instanceof SplitGroup g
                        ? "- Engine instances: " + g.getChildCount() + ", in parallel (split run; HdrHistograms merged, so percentiles are exact)\n"
                        : "- Engine instances: 1\n");
        summary(md, s);
        verdict(md, s.analysis().sloVerdict());
        capacity(md, s.analysis().capacity());
        failures(md, s.analysis().failureSummary());
        cases(md, s.stats(), s.expectations());
        if (!run.getHeaderNames().isEmpty()) {
            md.append("\nRequest headers sent: ").append(String.join(", ", run.getHeaderNames()))
                    .append(" (values not included).\n");
        }
        return md.toString();
    }

    private static void summary(StringBuilder md, RunSnapshot s) {
        md.append("\n## Summary\n\n| Metric | Value |\n|---|---|\n");
        StatsTable stats = s.stats();
        if (stats == null) {
            md.append("| Requests | ").append(DASH).append(" |\n");
            return;
        }
        CaseStats total = stats.aggregated();
        md.append("| Requests | ").append(total.requests()).append(" |\n")
                .append("| Requests per second | ").append(Numbers.plain(Numbers.round(total.requestsPerSecond(), 2))).append(" |\n")
                .append("| Average response | ").append(ms(total.averageMs())).append(" |\n")
                .append("| p95 response | ").append(ms(total.p95Ms())).append(" |\n")
                .append("| p99 response | ").append(ms(total.percentile(99.0))).append(" |\n")
                .append("| Problem rate (5xx, 429, connection) | ").append(pct(s.analysis().problemRate())).append(" |\n");
        OutcomeSummary outcomes = s.analysis().outcomeSummary();
        if (outcomes != null) {
            md.append("| Handled correctly | ").append(pct(outcomes.handledCorrectlyPct())).append(" |\n")
                    .append("| Unexpected answers | ").append(pct(outcomes.unexpectedOutcomeRatePct())).append(" |\n");
        }
    }

    private static void verdict(StringBuilder md, SloVerdict verdict) {
        if (verdict == null) {
            return;
        }
        md.append("\n## SLO verdict: ").append(verdict.verdict() == null ? "not measured" : verdict.verdict())
                .append("\n\n| Threshold | Target | Actual | Result |\n|---|---|---|---|\n");
        for (SloVerdict.Check c : verdict.checks()) {
            String sign = "<=".equals(c.comparison()) ? "≤" : "≥";
            String result = c.passed() == null ? "not measured" : c.passed() ? "✅ met" : "❌ missed";
            md.append("| ").append(c.label()).append(" | ").append(sign).append(' ').append(Numbers.plain(c.target()))
                    .append(' ').append(c.unit()).append(" | ")
                    .append(c.actual() == null ? DASH : Numbers.plain(c.actual()) + " " + c.unit())
                    .append(" | ").append(result).append(" |\n");
        }
    }

    private static void capacity(StringBuilder md, CapacityResult capacity) {
        if (capacity == null) {
            return;
        }
        md.append("\n## Capacity\n\n**").append(capacity.headline()).append("**\n\n");
        if (capacity.breakingPointUsers() != null) {
            md.append("Breaking point: ").append(capacity.breakingPointUsers()).append(" users (")
                    .append(capacity.breakingReason()).append(").\n\n");
        }
        md.append("| Users | Req/s | p95 ms | Server errors % | Verdict |\n|---|---|---|---|---|\n");
        for (CapacityStep step : capacity.steps()) {
            md.append("| ").append(step.plannedUsers()).append(" | ").append(Numbers.plain(step.rps())).append(" | ")
                    .append(step.p95Ms() == null ? DASH : Numbers.plain(Math.round(step.p95Ms()))).append(" | ")
                    .append(step.serverErrorRatePct() == null ? DASH : Numbers.plain(step.serverErrorRatePct())).append(" | ")
                    .append(step.complete() ? String.valueOf(step.verdict()) : "partial").append(" |\n");
        }
    }

    private static void failures(StringBuilder md, List<FailureGroup> groups) {
        if (groups.isEmpty()) {
            return;
        }
        md.append("\n## Failure types\n\n| Kind | Status | Total | Cases |\n|---|---|---|---|\n");
        for (FailureGroup g : groups) {
            String cases = g.cases().stream().limit(8).map(c -> c.caseName() + " ×" + c.occurrences())
                    .collect(Collectors.joining(", "));
            md.append("| ").append(g.meaning()).append(" | ").append(g.code() == null ? DASH : g.code()).append(" | ")
                    .append(g.total()).append(" | ").append(cases).append(" |\n");
        }
    }

    private static void cases(StringBuilder md, StatsTable stats, Map<String, Expectation> expectations) {
        if (stats == null) {
            return;
        }
        md.append("\n## Per case\n\n| Case | Expects | Requests | Failures | Avg ms | p95 ms |\n|---|---|---|---|---|---|\n");
        for (CaseStats c : stats.cases()) {
            Expectation e = expectations == null ? null : expectations.get(c.name());
            md.append("| ").append(c.name()).append(" | ")
                    .append(e == null ? DASH : e == Expectation.ACCEPT ? "2xx" : "4xx").append(" | ")
                    .append(c.requests()).append(" | ").append(c.failures()).append(" | ")
                    .append(c.requests() == 0 ? DASH : Numbers.plain(Math.round(c.averageMs()))).append(" | ")
                    .append(c.p95Ms() == null ? DASH : Numbers.plain(Math.round(c.p95Ms()))).append(" |\n");
        }
    }

    private static String ms(Double value) {
        return value == null ? DASH : Numbers.plain(Math.round(value)) + " ms";
    }

    private static String pct(Double value) {
        return value == null ? DASH : Numbers.plain(value) + "%";
    }
}
