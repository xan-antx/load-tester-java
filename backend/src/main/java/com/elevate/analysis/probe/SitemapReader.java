package com.elevate.analysis.probe;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.analysis.target.TargetGuard;

/**
 * Finds a website's {@code /sitemap.xml} and reads the page paths from it.
 *
 * <p>A regular sitemap ({@code <urlset>}) lists pages. A sitemap index ({@code <sitemapindex>})
 * lists other sitemaps: up to 5 of those are fetched (one level deep, each checked by the
 * target guard). Images, stylesheets and other assets are skipped; at most 20 paths are kept.
 *
 * <p>The XML parser refuses DOCTYPE declarations, so a hostile sitemap cannot use external
 * entities (XXE) to read files or make the server send requests.
 */
public final class SitemapReader {

    /** Most paths used for a website run. */
    public static final int MAX_PATHS = 20;
    private static final int MAX_SUB_SITEMAPS = 5;
    private static final List<String> NON_PAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg",
            ".pdf", ".css", ".js", ".xml", ".ico");

    private final GuardedHttpClient http;
    private final TargetGuard guard;

    /**
     * @param http  client that checks redirect hops
     * @param guard checks each sitemap URL before it is fetched
     */
    public SitemapReader(GuardedHttpClient http, TargetGuard guard) {
        this.http = http;
        this.guard = guard;
    }

    /**
     * Looks for {@code /sitemap.xml} at the root of {@code baseUrl}'s host.
     *
     * @param baseUrl any URL of the site
     * @return what was found
     */
    public SitemapResult find(String baseUrl) {
        String sitemapUrl = URI.create(baseUrl).resolve("/sitemap.xml").toString();
        try {
            HttpResponse<String> response = http.send("GET", sitemapUrl, RequestHeaders.NONE);
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() == 200 && type.contains("xml")) {
                return new SitemapResult(true, sitemapUrl, response.body(), null);
            }
            return new SitemapResult(false, sitemapUrl, null, null);
        } catch (IOException e) {
            return new SitemapResult(false, sitemapUrl, null, "unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SitemapResult(false, sitemapUrl, null, "unreachable");
        }
    }

    /**
     * @param xml a sitemap or sitemap index
     * @return page paths such as {@code /about}; empty when the XML cannot be parsed
     */
    public List<String> extractPaths(String xml) {
        return extractPaths(xml, 0);
    }

    private List<String> extractPaths(String xml, int depth) {
        Document document = parse(xml);
        if (document == null) {
            return List.of();
        }
        List<String> locations = locations(document);
        if ("sitemapindex".equals(localName(document.getDocumentElement()))) {
            return depth >= 1 ? List.of() : fromSubSitemaps(locations, depth);
        }
        List<String> paths = new ArrayList<>();
        for (String location : locations) {
            String path = pathOf(location);
            if (path != null && looksLikePage(path)) {
                paths.add(path);
            }
            if (paths.size() >= MAX_PATHS) {
                break;
            }
        }
        return paths;
    }

    private List<String> fromSubSitemaps(List<String> locations, int depth) {
        List<String> collected = new ArrayList<>();
        for (String url : locations.subList(0, Math.min(MAX_SUB_SITEMAPS, locations.size()))) {
            if (guard.check(url).isPresent()) {
                continue;
            }
            try {
                HttpResponse<String> response = http.send("GET", url, RequestHeaders.NONE);
                if (response.statusCode() == 200) {
                    collected.addAll(extractPaths(response.body(), depth + 1));
                }
            } catch (IOException | IllegalArgumentException e) {
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (collected.size() >= MAX_PATHS) {
                break;
            }
        }
        return collected.subList(0, Math.min(MAX_PATHS, collected.size()));
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return null;
        }
    }

    private static List<String> locations(Document document) {
        NodeList nodes = document.getElementsByTagNameNS("*", "loc");
        List<String> locations = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String text = nodes.item(i).getTextContent();
            if (text != null && !text.isBlank()) {
                locations.add(text.strip());
            }
        }
        return locations;
    }

    private static String localName(Element element) {
        return element.getLocalName() != null ? element.getLocalName() : element.getTagName();
    }

    private static String pathOf(String location) {
        try {
            String path = URI.create(location).getPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean looksLikePage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return NON_PAGE_EXTENSIONS.stream().noneMatch(lower::endsWith);
    }
}
