package com.elevate.analysis.probe;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.elevate.StubServer;
import com.elevate.StubServer.Answer;
import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.analysis.target.TargetGuard;

/** The reachability probe, manual redirects, type detection and sitemap reading against a stub server. */
class ProbeTest {

    private static final String SITEMAP = """
            <?xml version="1.0" encoding="UTF-8"?>
            <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              <url><loc>%s</loc></url><url><loc>%s</loc></url><url><loc>%s</loc></url>
            </urlset>""";

    private StubServer stub;
    private ReachabilityProbe probe;
    private SitemapReader sitemap;

    @BeforeEach
    void start() throws Exception {
        stub = new StubServer(Map.of(
                "/api/login", method -> method.equals("OPTIONS")
                        ? new Answer(200, null, "", Map.of("Allow", "OPTIONS, POST"))
                        : new Answer(405, "text/html", "<p>no</p>", Map.of("Allow", "OPTIONS, POST")),
                "/page", method -> new Answer(200, "text/html; charset=utf-8", "<html></html>", Map.of()),
                "/moved", method -> new Answer(302, null, "", Map.of("Location", "/page")),
                "/to-metadata", method -> new Answer(302, null, "", Map.of("Location", "http://169.254.169.254/latest")),
                "/broken", method -> Answer.json(503, "{}"),
                "/sitemap.xml", method -> new Answer(200, "application/xml", SITEMAP.formatted(
                        "http://x/about", "http://x/logo.png", "http://x/"), Map.of())));
        TargetGuard guard = new TargetGuard(true, host -> InetAddress.getAllByName(host));
        GuardedHttpClient http = new GuardedHttpClient(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build(), guard, Duration.ofSeconds(3));
        probe = new ReachabilityProbe(http);
        sitemap = new SitemapReader(http, guard);
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void postOnlyEndpointIsReachableButMethodNotAllowed() {
        SanityResult r = probe.check(stub.url("/api/login"), RequestHeaders.NONE);
        assertThat(r.reachable()).isTrue();
        assertThat(r.methodNotAllowed()).isTrue();
        assertThat(r.statusCode()).isEqualTo(405);
        assertThat(r.allowedMethods()).isEqualTo("OPTIONS, POST");
        assertThat(r.attempts()).extracting(ProbeAttempt::method).containsExactly("HEAD", "GET", "OPTIONS");

        TypeDetection type = TypeDetector.detect(r.finalUrl(), r.attempts());
        assertThat(type.classification()).isEqualTo("api");
        assertThat(type.confidence()).isEqualTo(1.0);
        assertThat(type.signals()).extracting(TypeDetection.Signal::name)
                .containsExactly("write_only_methods", "get_not_allowed", "path_hint");
    }

    @Test
    void htmlPageIsAWebsiteAndHeadIsEnough() {
        SanityResult r = probe.check(stub.url("/page"), RequestHeaders.NONE);
        assertThat(r.checkedWithMethod()).isEqualTo("HEAD");
        assertThat(r.attempts()).hasSize(1);
        assertThat(TypeDetector.detect(r.finalUrl(), r.attempts()).classification()).isEqualTo("website");
    }

    @Test
    void redirectsAreFollowedHopByHop() {
        SanityResult r = probe.check(stub.url("/moved"), RequestHeaders.NONE);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.finalUrl()).endsWith("/page");
    }

    @Test
    void redirectToABlockedHostIsRefusedBeforeConnecting() {
        SanityResult r = probe.check(stub.url("/to-metadata"), RequestHeaders.NONE);
        assertThat(r.blocked()).isTrue();
        assertThat(r.reachable()).isFalse();
        assertThat(r.error()).startsWith("Redirected to a blocked target: '169.254.169.254'");
    }

    @Test
    void serverErrorsAndDeadHostsAreUnreachable() {
        assertThat(probe.check(stub.url("/broken"), RequestHeaders.NONE).reachable()).isFalse();
        SanityResult dead = probe.check("http://127.0.0.1:9/", RequestHeaders.NONE);
        assertThat(dead.reachable()).isFalse();
        assertThat(dead.error()).contains("ConnectException");
        assertThat(probe.check("ftp://x/", RequestHeaders.NONE).validFormat()).isFalse();
        assertThat(ReachabilityProbe.isValidFormat("https://example.com")).isTrue();
        assertThat(ReachabilityProbe.isValidFormat("not a url")).isFalse();
    }

    @Test
    void unknownWithoutSignals() {
        TypeDetection t = TypeDetector.detect("http://x/", List.of(new ProbeAttempt("HEAD", 200, "", "")));
        assertThat(t.classification()).isEqualTo("unknown");
        TypeDetection json = TypeDetector.detect("http://x/", List.of(new ProbeAttempt("GET", 200, "application/json", "")));
        assertThat(json.classification()).isEqualTo("api");
    }

    @Test
    void sitemapIsFoundAndAssetsAreSkipped() {
        SitemapResult found = sitemap.find(stub.url("/page"));
        assertThat(found.found()).isTrue();
        assertThat(sitemap.extractPaths(found.raw())).containsExactly("/about", "/");
        assertThat(sitemap.find("http://127.0.0.1:9/").error()).isEqualTo("unreachable");
    }

    @Test
    void sitemapIndexIsFollowedOneLevel() {
        String index = """
                <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                  <sitemap><loc>%s</loc></sitemap>
                </sitemapindex>""".formatted(stub.url("/sitemap.xml"));
        assertThat(sitemap.extractPaths(index)).containsExactly("/about", "/");
    }

    @Test
    void doctypeIsRefusedSoXxeCannotWork() {
        String evil = """
                <?xml version="1.0"?>
                <!DOCTYPE urlset [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <urlset><url><loc>&x;</loc></url></urlset>""";
        assertThat(sitemap.extractPaths(evil)).isEmpty();
        assertThat(sitemap.extractPaths("not xml")).isEmpty();
    }
}
