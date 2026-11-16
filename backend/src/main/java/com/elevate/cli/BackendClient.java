package com.elevate.cli;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to the backend's REST API, exactly as the browser does. Any failure becomes a
 * {@link CliException} naming the request.
 */
public class BackendClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String base;
    private final HttpClient http;
    private final JsonMapper json;

    /**
     * @param base backend base URL, e.g. {@code http://localhost:8080}
     * @param http HTTP client
     * @param json JSON mapper
     */
    public BackendClient(String base, HttpClient http, JsonMapper json) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.http = http;
        this.json = json;
    }

    /** GET a JSON resource. */
    public JsonNode get(String path) {
        return parse("GET", path, send("GET", path, HttpRequest.newBuilder(uri(path)).GET()));
    }

    /** GET a text resource (the Markdown report). */
    public String getText(String path) {
        return send("GET", path, HttpRequest.newBuilder(uri(path)).GET()).body();
    }

    /** POST a JSON body and read the JSON answer. */
    public JsonNode post(String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return parse("POST", path, send("POST", path, request));
    }

    private HttpResponse<String> send(String method, String path, HttpRequest.Builder request) {
        HttpResponse<String> response;
        try {
            response = http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new CliException("cannot reach the backend (" + method + " " + base + path + "): "
                    + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("interrupted while calling the backend");
        }
        if (response.statusCode() >= 400) {
            String reason = "no details";
            try {
                JsonNode error = json.readTree(response.body()).get("error");
                if (error != null && error.isString()) {
                    reason = error.stringValue();
                }
            } catch (JacksonException e) {
                reason = "no details";
            }
            throw new CliException(method + " " + base + path + " returned " + response.statusCode() + ": " + reason);
        }
        return response;
    }

    private JsonNode parse(String method, String path, HttpResponse<String> response) {
        try {
            return json.readTree(response.body());
        } catch (JacksonException e) {
            throw new CliException(method + " " + base + path + " did not return JSON");
        }
    }

    private URI uri(String path) {
        return URI.create(base + path);
    }
}
