package com.elevate.analysis.probe;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.elevate.analysis.headers.RequestHeaders;

/**
 * Checks that a target answers, using read-only methods only: HEAD, then GET, then OPTIONS
 * — never POST, which a real API may act on.
 *
 * <p>Many API endpoints (especially POST-only ones such as {@code /auth/login}) answer HEAD
 * and GET with 404 or 405 although they are live. Those are not treated as unreachable: the
 * result says "reachable, method not allowed" and lists the {@code Allow} header. Every
 * response is kept in {@code attempts} for {@link TypeDetector}.
 */
public final class ReachabilityProbe {

    /** The probe methods, in order. All are read-only by definition. */
    public static final List<String> PROBE_METHODS = List.of("HEAD", "GET", "OPTIONS");

    private final GuardedHttpClient http;

    /** @param http client that checks every redirect hop */
    public ReachabilityProbe(GuardedHttpClient http) {
        this.http = http;
    }

    /** Whether {@code url} is an absolute http(s) URL with a host. */
    public static boolean isValidFormat(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getRawAuthority() != null && !uri.getRawAuthority().isEmpty();
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * @param url     the target (already allowed by the target guard)
     * @param headers request headers to send; their values never appear in the result
     * @return the probe result
     */
    public SanityResult check(String url, RequestHeaders headers) {
        if (!isValidFormat(url)) {
            return SanityResult.malformed();
        }
        List<Answer> answers = new ArrayList<>();
        SanityResult lastError = null;
        for (String method : PROBE_METHODS) {
            HttpResponse<String> response;
            try {
                response = http.send(method, url, headers);
            } catch (GuardedHttpClient.BlockedRedirectException e) {
                return SanityResult.failed(method, e.getMessage(), true);
            } catch (IOException e) {
                lastError = SanityResult.failed(method, headers.redact(describe(e)), false);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return SanityResult.failed(method, "interrupted", false);
            }
            answers.add(new Answer(method, response));
            if (!method.equals("OPTIONS") && !isRefusal(response.statusCode())) {
                return result(answers.getLast(), answers, false);
            }
        }
        if (answers.isEmpty()) {
            return lastError;
        }
        return result(answers.getLast(), answers, true);
    }

    /** Builds the result from the deciding answer; "refused" means HEAD or GET got 404 or 405. */
    private static SanityResult result(Answer deciding, List<Answer> answers, boolean triedEveryMethod) {
        List<ProbeAttempt> attempts = answers.stream().map(Answer::attempt).toList();
        HttpResponse<String> response = deciding.response();
        Map<String, String> headers = new LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> headers.put(canonical(name), String.join(", ", values)));
        List<Answer> refused = answers.stream()
                .filter(a -> !a.method().equals("OPTIONS") && isRefusal(a.response().statusCode())).toList();
        if (!triedEveryMethod || refused.isEmpty()) {
            return new SanityResult(true, response.statusCode() < 500, response.statusCode(), response.uri().toString(),
                    headers, deciding.method(), attempts, null, null, null, null);
        }
        String allow = deciding.attempt().allow();
        boolean anyAnswer = answers.stream().anyMatch(a -> a.response().statusCode() < 500);
        return new SanityResult(true, anyAnswer, refused.getLast().response().statusCode(),
                response.uri().toString(), headers, deciding.method(), attempts, true,
                allow.isEmpty() ? null : allow, null, null);
    }

    /**
     * java.net.http reports header names in lower case; show them the conventional way
     * ({@code content-type} → {@code Content-Type}), as the Python version does.
     */
    static String canonical(String name) {
        StringBuilder out = new StringBuilder(name.length());
        boolean upper = true;
        for (char c : name.toCharArray()) {
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = c == '-';
        }
        return out.toString();
    }

    private static boolean isRefusal(int status) {
        return status == 404 || status == 405;
    }

    private static String describe(IOException e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** One probe method and the response it got. */
    private record Answer(String method, HttpResponse<String> response) {
        ProbeAttempt attempt() {
            var h = response.headers();
            String allow = h.firstValue("Allow").or(() -> h.firstValue("Access-Control-Allow-Methods")).orElse("");
            return new ProbeAttempt(method, response.statusCode(), h.firstValue("Content-Type").orElse("").toLowerCase(),
                    allow);
        }
    }
}
