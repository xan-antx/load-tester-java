package com.elevate.analysis.probe;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies a target as an API or a website from the probe's responses.
 *
 * <p>Signals (weight): a JSON response from any probe (api 0.8), otherwise an HTML page
 * from a successful HEAD/GET (website 0.7); methods that allow writes but not GET (api 0.8);
 * HEAD/GET answered 405 (api 0.6); an API-looking path such as {@code /api/} (api 0.6).
 * The higher total wins; confidence is that total capped at 1.0.
 */
public final class TypeDetector {

    /** Path fragments typical of APIs. */
    public static final List<String> API_PATH_HINTS = List.of("/api/", "/v1/", "/v2/", "/graphql", "/rest/");

    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private TypeDetector() {
    }

    /**
     * @param url      the final URL of the probe
     * @param attempts every probe response
     * @return the classification
     */
    public static TypeDetection detect(String url, List<ProbeAttempt> attempts) {
        List<TypeDetection.Signal> signals = new ArrayList<>(attemptSignals(attempts));
        String path = URI.create(url).getPath();
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (API_PATH_HINTS.stream().anyMatch(lowerPath::contains)) {
            signals.add(new TypeDetection.Signal("path_hint", "api", 0.6));
        }
        double api = score(signals, "api");
        double site = score(signals, "website");
        if (api == 0 && site == 0) {
            return new TypeDetection("unknown", 0.0, signals);
        }
        return api >= site
                ? new TypeDetection("api", Math.min(api, 1.0), signals)
                : new TypeDetection("website", Math.min(site, 1.0), signals);
    }

    /**
     * Error pages and OPTIONS replies are usually HTML or empty whatever the resource is, so
     * HTML only counts when a HEAD/GET actually returned a page; JSON counts from any reply.
     */
    private static List<TypeDetection.Signal> attemptSignals(List<ProbeAttempt> attempts) {
        List<TypeDetection.Signal> signals = new ArrayList<>();
        if (attempts.stream().anyMatch(a -> a.contentType().contains("application/json"))) {
            signals.add(new TypeDetection.Signal("content_type_json", "api", 0.8));
        } else if (attempts.stream().anyMatch(a -> a.contentType().contains("text/html") && isHeadOrGet(a)
                && a.statusCode() >= 200 && a.statusCode() < 400)) {
            signals.add(new TypeDetection.Signal("content_type_html", "website", 0.7));
        }
        Set<String> allowed = new HashSet<>();
        attempts.forEach(a -> Arrays.stream(a.allow().split(","))
                .map(m -> m.strip().toUpperCase(Locale.ROOT)).filter(m -> !m.isEmpty()).forEach(allowed::add));
        if (!allowed.isEmpty() && !allowed.contains("GET") && allowed.stream().anyMatch(WRITE_METHODS::contains)) {
            signals.add(new TypeDetection.Signal("write_only_methods", "api", 0.8));
        }
        if (attempts.stream().anyMatch(a -> isHeadOrGet(a) && a.statusCode() == 405)) {
            signals.add(new TypeDetection.Signal("get_not_allowed", "api", 0.6));
        }
        return signals;
    }

    private static boolean isHeadOrGet(ProbeAttempt a) {
        return a.method().equals("HEAD") || a.method().equals("GET");
    }

    private static double score(List<TypeDetection.Signal> signals, String kind) {
        return signals.stream().filter(s -> s.kind().equals(kind)).mapToDouble(TypeDetection.Signal::weight).sum();
    }
}
