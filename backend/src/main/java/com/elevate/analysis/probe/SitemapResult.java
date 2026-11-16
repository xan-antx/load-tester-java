package com.elevate.analysis.probe;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Whether a website has a {@code /sitemap.xml}.
 *
 * @param found      a 200 XML answer was received
 * @param sitemapUrl where it was looked for
 * @param raw        the XML, when found
 * @param error      {@code unreachable} when the request failed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SitemapResult(boolean found, String sitemapUrl, String raw, String error) {
}
