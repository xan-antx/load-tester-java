package com.elevate.analysis.probe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.analysis.target.TargetGuard;

/**
 * Sends one read-only request and follows redirects <b>by hand</b>, so every hop is checked
 * by the {@link TargetGuard} before it is requested. (The client's own redirect handling
 * would already have connected to a blocked host by the time we could look.)
 */
public final class GuardedHttpClient {

    /** Redirect hops followed before giving up (the last answer is returned). */
    public static final int MAX_REDIRECTS = 5;

    private final HttpClient client;
    private final TargetGuard guard;
    private final Duration timeout;

    /**
     * @param client  an HTTP client that does <b>not</b> follow redirects
     * @param guard   checks each redirect target
     * @param timeout per-request timeout
     */
    public GuardedHttpClient(HttpClient client, TargetGuard guard, Duration timeout) {
        this.client = client;
        this.guard = guard;
        this.timeout = timeout;
    }

    /**
     * @param method  HTTP method (HEAD, GET or OPTIONS)
     * @param url     the first URL (already checked by the caller)
     * @param headers request headers to send
     * @return the final response, with its body as text
     * @throws BlockedRedirectException when a redirect points at a blocked host
     * @throws IOException              when the server cannot be reached
     * @throws InterruptedException     when the calling thread is interrupted
     */
    public HttpResponse<String> send(String method, String url, RequestHeaders headers)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String currentMethod = method;
        HttpResponse<String> response = null;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            response = client.send(request(currentMethod, uri, headers), HttpResponse.BodyHandlers.ofString());
            Optional<String> location = response.headers().firstValue("Location");
            int status = response.statusCode();
            if (status < 300 || status >= 400 || location.isEmpty()) {
                return response;
            }
            URI next = uri.resolve(location.get());
            Optional<String> problem = guard.check(next.toString());
            if (problem.isPresent()) {
                throw new BlockedRedirectException("Redirected to a blocked target: " + problem.get());
            }
            uri = next;
            if (status == 303) {
                currentMethod = "GET";
            }
        }
        return response;
    }

    private HttpRequest request(String method, URI uri, RequestHeaders headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.values().forEach(builder::header);
        return builder.build();
    }

    /** A redirect pointed at an address the target guard blocks. */
    public static final class BlockedRedirectException extends IOException {
        BlockedRedirectException(String message) {
            super(message);
        }
    }
}
