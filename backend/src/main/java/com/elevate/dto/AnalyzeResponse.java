package com.elevate.dto;

import com.elevate.analysis.probe.SanityResult;
import com.elevate.analysis.probe.SitemapResult;
import com.elevate.analysis.probe.TypeDetection;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of {@code /api/analyze}. Fields that do not apply are left out.
 *
 * @param sanity        the reachability probe
 * @param typeDetection API or website
 * @param sitemap       websites only
 * @param error         why the target was refused
 * @param blockedTarget a redirect pointed at a blocked host
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalyzeResponse(SanityResult sanity, TypeDetection typeDetection, SitemapResult sitemap, String error,
                              Boolean blockedTarget) {
}
