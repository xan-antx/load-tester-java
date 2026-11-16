package com.elevate.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.elevate.analysis.edgecase.CustomCaseClassifier;
import com.elevate.analysis.edgecase.EdgeCaseSuite;
import com.elevate.analysis.probe.GuardedHttpClient;
import com.elevate.analysis.probe.ReachabilityProbe;
import com.elevate.analysis.probe.SitemapReader;
import com.elevate.analysis.target.TargetGuard;

/**
 * Wires the analysis classes, which are plain Java (no Spring annotations, so they are easy
 * to unit-test), into Spring beans.
 */
@Configuration
public class BeanConfig {

    /** Timeout of each probe request. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** The wall clock (a bean, so tests could fix it). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** The SSRF guard, configured by {@code ALLOW_PRIVATE_TARGETS}. */
    @Bean
    public TargetGuard targetGuard(AppProperties properties) {
        return new TargetGuard(properties.allowPrivateTargets());
    }

    /** HTTP client for probes; it never follows redirects itself (see {@link GuardedHttpClient}). */
    @Bean
    public HttpClient probeHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(PROBE_TIMEOUT)
                .build();
    }

    /** Probe client that checks every redirect hop. */
    @Bean
    public GuardedHttpClient guardedHttpClient(HttpClient probeHttpClient, TargetGuard targetGuard) {
        return new GuardedHttpClient(probeHttpClient, targetGuard, PROBE_TIMEOUT);
    }

    /** HEAD → GET → OPTIONS reachability probe. */
    @Bean
    public ReachabilityProbe reachabilityProbe(GuardedHttpClient http) {
        return new ReachabilityProbe(http);
    }

    /** Sitemap discovery and parsing. */
    @Bean
    public SitemapReader sitemapReader(GuardedHttpClient http, TargetGuard targetGuard) {
        return new SitemapReader(http, targetGuard);
    }

    /** The edge-case strategies in their standard order. */
    @Bean
    public EdgeCaseSuite edgeCaseSuite() {
        return EdgeCaseSuite.standard();
    }

    /** Validator for user-written cases. */
    @Bean
    public CustomCaseClassifier customCaseClassifier(EdgeCaseSuite edgeCaseSuite) {
        return new CustomCaseClassifier(edgeCaseSuite);
    }
}
