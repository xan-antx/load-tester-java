package com.elevate.service;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.stereotype.Service;

import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.analysis.probe.ReachabilityProbe;
import com.elevate.analysis.probe.SanityResult;
import com.elevate.analysis.probe.SitemapReader;
import com.elevate.analysis.probe.SitemapResult;
import com.elevate.analysis.probe.TypeDetection;
import com.elevate.analysis.probe.TypeDetector;
import com.elevate.analysis.target.TargetGuard;
import com.elevate.dto.AnalyzeResponse;
import com.elevate.exception.TargetBlockedException;

import tools.jackson.databind.JsonNode;

/** Step 1 of the workflow: is the target reachable, and is it an API or a website? */
@Service
public class TargetAnalysisService {

    private final TargetGuard guard;
    private final ReachabilityProbe probe;
    private final SitemapReader sitemap;

    /**
     * @param guard   SSRF guard
     * @param probe   HEAD → GET → OPTIONS probe
     * @param sitemap sitemap discovery for websites
     */
    public TargetAnalysisService(TargetGuard guard, ReachabilityProbe probe, SitemapReader sitemap) {
        this.guard = guard;
        this.probe = probe;
        this.sitemap = sitemap;
    }

    /**
     * Probes a URL.
     *
     * @throws TargetBlockedException when the target guard refuses it
     */
    public SanityResult sanityCheck(String url) {
        checkGuard(url);
        return probe.check(url, RequestHeaders.NONE);
    }

    /**
     * Probes a URL and classifies it; looks for a sitemap when it is a website.
     *
     * @param url        the target
     * @param rawHeaders optional request headers (JSON)
     * @return the result, and whether the target could be analyzed (200) or not (400)
     */
    public Analysis analyze(String url, JsonNode rawHeaders) {
        RequestHeaders headers = RequestHeaders.clean(rawHeaders);
        checkGuard(url);
        SanityResult sanity = probe.check(url, headers);
        if (!sanity.validFormat() || !sanity.reachable()) {
            boolean blocked = Boolean.TRUE.equals(sanity.blocked());
            return new Analysis(false, new AnalyzeResponse(sanity, null, null, blocked ? sanity.error() : null,
                    blocked ? true : null));
        }
        TypeDetection type = TypeDetector.detect(sanity.finalUrl(), sanity.attempts());
        SitemapResult found = "website".equals(type.classification()) ? sitemap.find(sanity.finalUrl()) : null;
        return new Analysis(true, new AnalyzeResponse(sanity, type, found, null, null));
    }

    /** Python checks the guard only when the URL has a host; a malformed URL is reported by the probe. */
    private void checkGuard(String url) {
        try {
            if (new URI(url).getHost() == null) {
                return;
            }
        } catch (URISyntaxException e) {
            return;
        }
        guard.check(url).ifPresent(problem -> {
            throw new TargetBlockedException(problem);
        });
    }

    /**
     * @param ok   the target was reachable and analyzed
     * @param body the response body
     */
    public record Analysis(boolean ok, AnalyzeResponse body) {
    }
}
