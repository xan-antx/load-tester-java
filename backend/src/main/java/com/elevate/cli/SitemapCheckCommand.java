package com.elevate.cli;

import java.io.PrintStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;

import com.elevate.analysis.probe.GuardedHttpClient;
import com.elevate.analysis.probe.SitemapReader;
import com.elevate.analysis.target.TargetGuard;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * {@code check-sitemap}: a manual check of sitemap discovery and path extraction (port of the
 * Python {@code scripts/check_sitemap_paths.py} and {@code check_extract_paths.py}). Asks the
 * backend to analyze a website, prints what it found, and extracts the page paths a website
 * run would use with the same {@link SitemapReader} the backend uses.
 */
@Command(name = "check-sitemap", mixinStandardHelpOptions = true,
        description = "Show the sitemap the backend finds for a website and the page paths extracted from it.")
public class SitemapCheckCommand implements Callable<Integer> {

    @Option(names = "--api", defaultValue = CliConfig.DEFAULT_API, description = "backend base URL")
    String api;
    @Option(names = "--url", defaultValue = "https://scentitude.in", description = "a website with a sitemap")
    String url;

    private final PrintStream out;
    private final PrintStream err;
    private BackendClient backend;

    /** For picocli: prints to the console. */
    public SitemapCheckCommand() {
        this(System.out, System.err, null);
    }

    /**
     * @param out     results
     * @param err     errors
     * @param backend the REST API, or {@code null} to talk to {@code --api}
     */
    public SitemapCheckCommand(PrintStream out, PrintStream err, BackendClient backend) {
        this.out = out;
        this.err = err;
        this.backend = backend;
    }

    @Override
    public Integer call() {
        try {
            if (backend == null) {
                backend = new BackendClient(api, HttpClient.newHttpClient(), JsonMapper.builder().build());
            }
            JsonNode analysis = backend.post("/api/analyze", JsonNodeFactory.instance.objectNode().put("url", url));
            JsonNode sitemap = analysis.path("sitemap");
            out.println("Sitemap found: " + (sitemap.has("found") ? sitemap.get("found").asBoolean() : "None"));
            JsonNode raw = sitemap.get("raw");
            if (raw == null || !raw.isString() || raw.stringValue().isEmpty()) {
                out.println("No raw sitemap content — can't test path extraction");
                return CliRunner.EXIT_PASS;
            }
            String xml = raw.stringValue();
            out.println("Raw sitemap length: " + xml.length());
            out.println("First 300 chars:\n " + xml.substring(0, Math.min(300, xml.length())));
            List<String> paths = reader().extractPaths(xml);
            out.println("Extracted paths: " + paths);
            out.println("Total paths found: " + paths.size());
            return CliRunner.EXIT_PASS;
        } catch (CliException e) {
            err.println("ERROR: " + e.getMessage());
            return CliRunner.EXIT_ERROR;
        }
    }

    /** The backend's sitemap reader, with the strict target guard for sub-sitemaps. */
    private static SitemapReader reader() {
        TargetGuard guard = new TargetGuard(false);
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        return new SitemapReader(new GuardedHttpClient(client, guard, Duration.ofSeconds(5)), guard);
    }
}
