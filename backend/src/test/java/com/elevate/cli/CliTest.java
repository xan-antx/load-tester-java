package com.elevate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Config loading, case selection, exit-code decisions, output files and the run flow (with a mocked backend). */
class CliTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ObjectNode tree(String json) {
        return (ObjectNode) JSON.readTree(json);
    }

    private static CliConfig config(String json) {
        return CliConfig.from(tree(json), null, null);
    }

    private static final String BASE = "{\"target\":{\"url\":\"http://m/api/login\",\"i_own_target\":true},"
            + "\"sample\":{\"u\":\"x\"},\"slo\":{\"p95_ms\":500}";

    @Test
    void configNeedsUrlOwnershipAndKnownSloKeys() {
        assertThatThrownBy(() -> config("{}")).hasMessageContaining("no target URL");
        assertThatThrownBy(() -> config("{\"target\":{\"url\":\"http://x/\"}}")).hasMessageContaining("i_own_target");
        assertThatThrownBy(() -> config(BASE.replace("p95_ms", "p96") + "}")).hasMessage("unknown SLO threshold(s): p96");
        assertThatThrownBy(() -> config(BASE + ",\"load\":{\"mode\":\"turbo\"}}")).hasMessage("load.mode must be \"fixed\" or \"capacity\"");
        CliConfig cfg = config(BASE + "}");
        assertThat(cfg.api()).isEqualTo(CliConfig.DEFAULT_API);
        assertThat(cfg.outDir()).isEqualTo(Path.of("loadtest-results"));
        assertThat(cfg.expectedSeconds()).isEqualTo(30);
        assertThat(CliConfig.from(tree(BASE + "}"), "http://env:1", 5).api()).isEqualTo("http://env:1");
    }

    @Test
    void capacityConfigKeepsOnlyPlanKeys() {
        CliConfig cfg = config(BASE + ",\"load\":{\"mode\":\"capacity\"},\"capacity\":{\"start_users\":20,\"step_users\":20,"
                + "\"step_duration_seconds\":12,\"max_users\":200,\"min_users\":60}}");
        assertThat(cfg.capacityMode()).isTrue();
        assertThat(cfg.capacity().has("min_users")).isFalse();
        assertThat(cfg.requiredUsers()).isEqualTo(60);
        assertThat(cfg.expectedSeconds()).isEqualTo(120);
    }

    @Test
    void caseSelectionIncludeExclude() {
        JsonNode generated = JSON.readTree("[{\"label\":\"a\"},{\"label\":\"b\"},{\"label\":\"c\"}]");
        assertThat(CliRunner.selectLabels(generated, config(BASE + ",\"cases\":{\"exclude\":[\"b\"]}}"))).containsExactly("a", "c");
        assertThat(CliRunner.selectLabels(generated, config(BASE + ",\"cases\":{\"include\":[\"c\"]}}"))).containsExactly("c");
        assertThatThrownBy(() -> CliRunner.selectLabels(generated, config(BASE + ",\"cases\":{\"include\":[\"z\"]}}")))
                .hasMessage("unknown case label(s) in cases.include: z");
        assertThatThrownBy(() -> CliRunner.selectLabels(generated, config(BASE + ",\"cases\":{\"exclude\":[\"a\",\"b\",\"c\"]}}")))
                .hasMessage("no cases left to send after include/exclude");
        assertThatThrownBy(() -> CliRunner.selectLabels(generated, config(BASE + ",\"cases\":{\"expect\":{\"a\":\"maybe\"}}}")))
                .hasMessageStartingWith("cases.expect values must be accept or reject");
    }

    @Test
    void exitCodes() {
        CliConfig fixed = config(BASE + "}");
        assertThat(CliRunner.decide(fixed, JSON.readTree("{\"status\":\"completed\",\"slo_verdict\":{\"verdict\":\"PASS\"}}"))).isZero();
        assertThat(CliRunner.decide(fixed, JSON.readTree("{\"status\":\"completed\",\"slo_verdict\":{\"verdict\":\"FAIL\"}}"))).isEqualTo(1);
        assertThat(CliRunner.decide(fixed, JSON.readTree("{\"status\":\"completed\",\"slo_verdict\":null}"))).isEqualTo(1);
        assertThat(CliRunner.decide(fixed, JSON.readTree("{\"status\":\"failed\"}"))).isEqualTo(2);
        CliConfig noSlo = config("{\"target\":{\"url\":\"http://m/\",\"i_own_target\":true}}");
        assertThat(CliRunner.decide(noSlo, JSON.readTree("{\"status\":\"completed\",\"slo_verdict\":null}"))).isZero();
        CliConfig capacity = config(BASE + ",\"load\":{\"mode\":\"capacity\"},\"capacity\":{\"start_users\":20,\"min_users\":60}}");
        assertThat(CliRunner.decide(capacity, JSON.readTree("{\"status\":\"completed\",\"capacity\":{\"max_sustainable_users\":120}}"))).isZero();
        assertThat(CliRunner.decide(capacity, JSON.readTree("{\"status\":\"completed\",\"capacity\":{\"max_sustainable_users\":40}}"))).isEqualTo(1);
    }

    @Test
    void runFlowAgainstAMockedBackend(@TempDir Path dir) {
        BackendClient backend = mock(BackendClient.class);
        when(backend.get("/api/health")).thenReturn(JSON.readTree("{\"status\":\"ok\"}"));
        when(backend.post(eq("/api/analyze"), any())).thenReturn(JSON.readTree("{\"type_detection\":{\"classification\":\"api\"}}"));
        when(backend.post(eq("/api/generate-edge-cases"), any())).thenReturn(JSON.readTree("{\"cases\":[{\"label\":\"valid_sample\"}]}"));
        when(backend.post(eq("/api/confirm-selection"), any())).thenReturn(JSON.readTree("{\"confirmed_cases\":[{\"label\":\"valid_sample\"}]}"));
        when(backend.post(eq("/api/start-load-test"), any())).thenReturn(JSON.readTree("{\"job_id\":\"run-123456789\"}"));
        when(backend.get("/api/load-test-status/run-123456789")).thenReturn(JSON.readTree("{\"status\":\"running\"}"),
                JSON.readTree("{\"status\":\"completed\",\"stats_csv\":\"Type,Name,Request Count,Requests/s\\n,Aggregated,10,2\\n\","
                        + "\"slo_verdict\":{\"verdict\":\"PASS\",\"checks\":[{\"label\":\"p95 response time\",\"comparison\":\"<=\","
                        + "\"target\":500,\"actual\":150.5,\"unit\":\"ms\",\"passed\":true}]}}"));
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        CliConfig cfg = config(BASE + ",\"output\":{\"dir\":\"" + dir.toString().replace("\\", "\\\\") + "\"}}");

        CliRunner.Result result = new CliRunner(backend, new PrintStream(printed), d -> { }).run(cfg);

        assertThat(result.exitCode()).isZero();
        assertThat(printed.toString()).contains("looks like: api").contains("Started run run-123456789");
        verify(backend).post(eq("/api/start-load-test"), any());
        List<Path> files = CliOutputs.write(cfg, result, "# report");
        assertThat(files.get(0).getFileName().toString()).isEqualTo("report-run-1234.md");
        assertThat(files.get(2)).content().contains("### SLO verdict: PASS").contains("≤ 500 ms").contains("✅ met");
        assertThat(CliOutputs.consoleSummary(cfg, result)).contains("requests 10").contains("<= 500 ms").contains("met");
    }

    @Test
    void websiteTargetsUseTheSitemap() {
        BackendClient backend = mock(BackendClient.class);
        when(backend.post(eq("/api/analyze"), any())).thenReturn(JSON.readTree(
                "{\"type_detection\":{\"classification\":\"website\"},\"sitemap\":{\"raw\":\"<urlset/>\"}}"));
        when(backend.post(eq("/api/start-website-load-test"), any())).thenReturn(JSON.readTree("{\"job_id\":\"w\"}"));
        when(backend.get("/api/load-test-status/w")).thenReturn(JSON.readTree("{\"status\":\"failed\"}"));
        CliRunner.Result r = new CliRunner(backend, new PrintStream(new ByteArrayOutputStream()), d -> { }).run(config(BASE + "}"));
        assertThat(r.exitCode()).isEqualTo(2);
    }

    @Test
    void commandLineFlagsOverrideTheConfigFile(@TempDir Path dir) throws Exception {
        Path yml = dir.resolve("c.yml");
        Files.writeString(yml, "target:\n  url: http://a/\nload:\n  users: 3\nslo:\n  p95_ms: 100\n");
        RunCommand command = new RunCommand();
        new CommandLine(command).parseArgs("--config", yml.toString(), "--users", "9", "--i-own-target",
                "--header", "Authorization: Bearer x", "--cases", "a,b", "--sample", "{\"u\":1}", "--slo-p95-ms", "250");
        ObjectNode merged = command.mergedConfig();
        assertThat(merged.at("/load/users").asInt()).isEqualTo(9);
        assertThat(merged.at("/target/i_own_target").asBoolean()).isTrue();
        assertThat(merged.at("/target/headers/Authorization").asString()).isEqualTo("Bearer x");
        assertThat(merged.at("/cases/include/1").asString()).isEqualTo("b");
        assertThat(merged.at("/sample/u").asInt()).isEqualTo(1);
        assertThat(merged.at("/slo/p95_ms").asDouble()).isEqualTo(250.0);
    }

    @Test
    void errorsExitWithTwo(@TempDir Path dir) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        RunCommand missing = new RunCommand(new PrintStream(new ByteArrayOutputStream()), new PrintStream(err));
        new CommandLine(missing).parseArgs("--config", dir.resolve("nope.yml").toString());
        assertThat(missing.call()).isEqualTo(2);
        assertThat(err.toString()).contains("cannot read config");
        RunCommand badHeader = new RunCommand();
        new CommandLine(badHeader).parseArgs("--header", "no-colon");
        assertThatThrownBy(badHeader::mergedConfig).hasMessageContaining("header must look like");
        assertThat(LoadTestCli.execute(new String[] {"run", "--url", "http://x/"})).isEqualTo(2);
        assertThat(VerdictTable.number(null, 1)).isEqualTo("-");
    }
}
