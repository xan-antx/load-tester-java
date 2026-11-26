package com.elevate.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.elevate.cli.RunCommand;
import com.elevate.mock.MockConfig;
import com.elevate.mock.MockTargetServer;
import com.elevate.model.JobType;
import com.elevate.model.RunSettings;
import com.elevate.model.RunStatus;
import com.elevate.model.TestRun;
import com.elevate.repository.TestRunRepository;
import com.elevate.service.RunStore;

import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The whole application on a random port against the Java mock, used exactly as the browser
 * and the CLI use it: real HTTP, real engine, real database (in memory). Runs are a few
 * seconds long.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiAgainstMockIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SAMPLE = "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30}";
    private static MockTargetServer mock;
    private static final List<String> COMPLETED = new ArrayList<>();

    @LocalServerPort
    int port;
    @Autowired
    TestRunRepository runs;
    @Autowired
    RunStore store;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startMock() throws Exception {
        mock = MockTargetServer.start(MockConfig.defaults().withPort(0).withRateLimit(100_000));
    }

    @AfterAll
    static void stopMock() {
        mock.close();
    }

    private record Reply(int status, JsonNode body) {
    }

    private Reply call(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), JSON.readTree(response.body()));
    }

    private JsonNode waitFor(String id) throws Exception {
        for (int i = 0; i < 120; i++) {
            JsonNode status = call("GET", "/api/load-test-status/" + id, null).body();
            if (RunStatus.valueOf(status.path("status").asString().toUpperCase()).isTerminal()) {
                return status;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("run " + id + " did not finish");
    }

    private ObjectNode startBody(String labelsJson, int users, int seconds) throws Exception {
        JsonNode confirmed = call("POST", "/api/confirm-selection", "{\"sample_input\":" + SAMPLE + ",\"selected_labels\":"
                + labelsJson + ",\"expectations\":{\"missing_expiresInMins\":\"accept\",\"null_expiresInMins\":\"accept\"}}").body();
        ObjectNode body = JSON.createObjectNode();
        body.put("url", mock.loginUrl()).put("i_own_target", true).put("users", users).put("spawn_rate", 20)
                .put("duration_seconds", seconds);
        body.set("confirmed_cases", confirmed.get("confirmed_cases"));
        body.set("slo", JSON.readTree("{\"p95_ms\":500,\"max_server_error_rate_pct\":1}"));
        return body;
    }

    private String allLabels() throws Exception {
        JsonNode cases = call("POST", "/api/generate-edge-cases", "{\"sample_input\":" + SAMPLE + "}").body().get("cases");
        List<String> labels = new ArrayList<>();
        cases.forEach(c -> labels.add("\"" + c.get("label").asString() + "\""));
        return "[" + String.join(",", labels) + "]";
    }

    @Test
    @Order(1)
    void analyzeClassifiesTheMockAsAnApi() throws Exception {
        Reply r = call("POST", "/api/analyze", "{\"url\":\"" + mock.loginUrl() + "\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().at("/type_detection/classification").asString()).isEqualTo("api");
        assertThat(r.body().at("/sanity/method_not_allowed").asBoolean()).isTrue();
    }

    @Test
    @Order(2)
    void fullRunFindsTheDeliberateBugsAndFailsTheSlo() throws Exception {
        Reply started = call("POST", "/api/start-load-test", startBody(allLabels(), 15, 4).toString());
        assertThat(started.status()).isEqualTo(202);
        String id = started.body().get("job_id").asString();

        Reply busy = call("POST", "/api/start-load-test", startBody("[\"valid_sample\"]", 1, 1).toString());
        assertThat(busy.status()).as("MAX_ACTIVE_RUNS=1").isEqualTo(429);
        assertThat(busy.body().get("busy").asBoolean()).isTrue();

        JsonNode run = waitFor(id);
        assertThat(run.get("status").asString()).isEqualTo("completed");
        List<String> kinds = new ArrayList<>();
        run.get("failure_summary").forEach(g -> kinds.add(g.get("kind").asString() + ":" + g.get("code").asInt()));
        assertThat(kinds).containsExactlyInAnyOrder("server_error:500", "accepted_invalid_input:200");
        JsonNode accepted = run.get("failure_summary").findParents("kind").stream()
                .filter(g -> g.get("kind").asString().equals("accepted_invalid_input")).findFirst().orElseThrow();
        assertThat(accepted.at("/cases/0/case").asString()).isEqualTo("very_long_string_username");
        assertThat(run.at("/outcome_summary/rejected_valid_input").asInt()).isZero();
        assertThat(run.at("/slo_verdict/verdict").asString()).isEqualTo("FAIL");
        assertThat(run.get("stats_csv").asString()).contains("Aggregated");
        assertThat(run.get("history_csv").asString()).startsWith("Timestamp,User Count");
        assertThat(run.get("failures_csv").asString()).contains("very_long_string_username");
        COMPLETED.add(id);
    }

    @Test
    @Order(3)
    void splitRunMergesTwoEngineInstances() throws Exception {
        Reply started = call("POST", "/api/start-load-test", startBody("[\"valid_sample\",\"missing_username\"]", 60, 3).toString());
        assertThat(started.body().get("is_group").asBoolean()).isTrue();
        JsonNode group = waitFor(started.body().get("job_id").asString());
        assertThat(group.get("status").asString()).isEqualTo("completed");
        assertThat(group.get("child_jobs")).hasSize(2);
        assertThat(group.get("p95_exact").asBoolean()).isTrue();
        // 60 users with short think times overload the mock's 8 workers, so latency may break
        // the SLO; what matters here is that the merged run is judged at all.
        assertThat(group.at("/slo_verdict/verdict").asString()).isIn("PASS", "FAIL");
        long sum = 0;
        for (JsonNode child : group.get("child_jobs")) {
            sum += aggregatedRequests(child.get("stats_csv").asString());
        }
        assertThat(aggregatedRequests(group.get("aggregated_stats_csv").asString())).isEqualTo(sum);
        COMPLETED.add(group.get("group_id").asString());
    }

    private static long aggregatedRequests(String csv) {
        return csv.lines().filter(l -> l.startsWith(",Aggregated,")).mapToLong(l -> Long.parseLong(l.split(",")[2]))
                .findFirst().orElseThrow();
    }

    @Test
    @Order(4)
    void cancelKeepsPartialResults() throws Exception {
        String id = call("POST", "/api/start-load-test", startBody("[\"valid_sample\"]", 5, 60).toString()).body()
                .get("job_id").asString();
        Thread.sleep(1500);
        assertThat(call("POST", "/api/cancel/" + id, null).status()).isEqualTo(202);
        JsonNode run = waitFor(id);
        assertThat(run.get("status").asString()).isEqualTo("cancelled");
        assertThat(aggregatedRequests(run.get("stats_csv").asString())).isPositive();
        assertThat(run.get("slo_verdict").isNull()).as("no verdict for a cancelled run").isTrue();
    }

    @Test
    @Order(5)
    void capacityRunStopsAtTheFirstFailingStep() throws Exception {
        ObjectNode body = startBody("[\"valid_sample\"]", 5, 10);
        body.put("mode", "capacity");
        body.set("slo", JSON.readTree("{\"p95_ms\":1}"));
        body.set("capacity", JSON.readTree("{\"start_users\":2,\"step_users\":2,\"step_duration_seconds\":5,\"max_users\":6}"));
        Reply started = call("POST", "/api/start-load-test", body.toString());
        assertThat(started.body().get("mode").asString()).isEqualTo("capacity");
        JsonNode run = waitFor(started.body().get("job_id").asString());
        assertThat(run.at("/capacity/breaking_point_users").asInt()).isEqualTo(2);
        assertThat(run.at("/capacity/headline").asString()).isEqualTo("Breaks already at the first step (2 users)");
        assertThat(run.at("/capacity/stopped_early").asBoolean()).isTrue();
        assertThat(run.get("slo_verdict").isNull()).isTrue();
    }

    @Test
    @Order(6)
    void historyCompareBaselineRegressionsAndReport() throws Exception {
        JsonNode history = call("GET", "/api/jobs", null).body();
        assertThat(history.get("jobs").size()).isGreaterThanOrEqualTo(3);
        assertThat(history.get("job_groups")).hasSize(1);
        String single = COMPLETED.get(0);
        String group = COMPLETED.get(1);

        Reply compare = call("POST", "/api/compare-jobs", "{\"job_ids\":[\"" + single + "\",\"" + group + "\"]}");
        assertThat(compare.status()).isEqualTo(200);
        assertThat(compare.body().get("jobs")).hasSize(2);
        assertThat(compare.body().at("/case_comparison/rows")).isNotEmpty();

        assertThat(call("POST", "/api/baseline", "{\"id\":\"" + group + "\"}").status()).isEqualTo(200);
        JsonNode regressions = call("GET", "/api/regressions/" + single, null).body();
        assertThat(regressions.get("baseline_id").asString()).isEqualTo(group);
        assertThat(regressions.at("/baseline/is_group").asBoolean()).isTrue();
        assertThat(regressions.has("p95_change_pct")).isTrue();

        HttpResponse<String> report = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/report/" + group)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(report.body()).contains("# Load test report").contains("Engine instances: 2").contains("## SLO verdict: ");
    }

    @Test
    @Order(7)
    void cliPassesTheCiGateAndFailsOnTheBugs(@TempDir Path out) {
        String api = "http://localhost:" + port;
        String[] gate = {"--api", api, "--url", mock.loginUrl(), "--i-own-target", "--sample", SAMPLE, "--users", "5",
            "--spawn-rate", "5", "--duration", "3", "--slo-p95-ms", "500", "--slo-server-error-pct", "1",
            "--out-dir", out.toString(), "--cases", "valid_sample,missing_username,null_password,zero_expiresInMins"};
        assertThat(runCli(gate)).as("only correctly handled cases").isZero();
        String[] all = {"--api", api, "--url", mock.loginUrl(), "--i-own-target", "--sample", SAMPLE, "--users", "10",
            "--spawn-rate", "10", "--duration", "3", "--slo-server-error-pct", "1", "--out-dir", out.toString()};
        assertThat(runCli(all)).as("the 500 bugs break the server-error SLO").isEqualTo(1);
        assertThat(out.resolve("summary.md")).exists();
    }

    private static int runCli(String[] args) {
        RunCommand command = new RunCommand(new PrintStream(new ByteArrayOutputStream()), System.err);
        return new CommandLine(command).execute(args);
    }

    @Test
    @Order(7)
    void seriesRunsBackToBack() throws Exception {
        ObjectNode body = startBody("[\"valid_sample\",\"missing_username\"]", 3, 2);
        body.put("repeat", 2);
        Reply started = call("POST", "/api/start-load-test", body.toString());
        assertThat(started.body().get("is_series").asBoolean()).isTrue();
        JsonNode series = waitFor(started.body().get("series_id").asString());
        assertThat(series.get("status").asString()).isEqualTo("completed");
        assertThat(series.get("runs")).hasSize(2);
        assertThat(series.at("/series_summary/repeats").asInt()).isEqualTo(2);
        assertThat(series.at("/series_summary/overall/p95/values")).hasSize(2);
        JsonNode first = call("GET", "/api/load-test-status/" + series.at("/run_ids/0").asString(), null).body();
        JsonNode second = call("GET", "/api/load-test-status/" + series.at("/run_ids/1").asString(), null).body();
        assertThat(second.get("created_at").asString()).as("the second repeat starts after the first")
                .isGreaterThanOrEqualTo(first.get("completed_at").asString());
    }

    @Test
    @Order(7)
    void websiteRunRequestsTheSitemapPages() throws Exception {
        String origin = mock.loginUrl().replace("/api/login", "");
        String sitemap = "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><url><loc>" + origin
                + "/health</loc></url><url><loc>" + origin + "/missing</loc></url><url><loc>" + origin
                + "/logo.png</loc></url></urlset>";
        ObjectNode body = JSON.createObjectNode();
        body.put("url", origin + "/").put("i_own_target", true).put("users", 2).put("spawn_rate", 2)
                .put("duration_seconds", 2).put("sitemap_raw", sitemap);
        Reply started = call("POST", "/api/start-website-load-test", body.toString());
        assertThat(started.status()).isEqualTo(202);
        assertThat(started.body().get("paths_used")).hasSize(2);
        JsonNode run = waitFor(started.body().get("job_id").asString());
        assertThat(run.get("job_type").asString()).isEqualTo("website");
        assertThat(run.get("stats_csv").asString()).contains("GET,/health,").contains("GET,/missing,");
        assertThat(run.at("/failure_summary/0/kind").asString()).isEqualTo("rejected");
        assertThat(run.at("/failure_summary/0/code").asInt()).isEqualTo(404);
        assertThat(run.get("outcome_summary").isNull()).isTrue();
    }

    @Test
    @Order(8)
    void leftoverRunsAreMarkedInterruptedOnStartup() {
        TestRun stale = new TestRun("stale-run", new RunSettings("http://x", JobType.API, 1, 1, 1, List.of()), "/", List.of(),
                null, null, java.time.Instant.now());
        stale.changeStatus(RunStatus.RUNNING, null, java.time.Instant.now());
        runs.save(stale);
        assertThat(store.markLeftoversInterrupted()).isPositive();
        TestRun after = runs.findById("stale-run").orElseThrow();
        assertThat(after.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(after.getError()).isEqualTo(RunStore.INTERRUPTED_REASON);
    }
}
