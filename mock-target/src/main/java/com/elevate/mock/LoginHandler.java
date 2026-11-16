package com.elevate.mock;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /api/login}: only POST does work; OPTIONS lists the allowed methods and every
 * other method gets 405 (like Flask), so the analyzer sees a POST-only API endpoint.
 *
 * <p>Every POST first spends 50–150 ms of "work" on the calling thread. The server runs
 * handlers on a fixed pool of {@link MockConfig#workers()} threads, so when more requests
 * arrive than the pool can serve (about 80 per second with 8 workers) the rest wait in the
 * pool's queue and response times climb, the way an overloaded real server's do.
 */
final class LoginHandler implements HttpHandler {

    private static final String ALLOW = "OPTIONS, POST";
    private static final Duration MIN_WORK = Duration.ofMillis(50);
    private static final Duration MAX_WORK = Duration.ofMillis(150);

    private final ObjectMapper json = JsonMapper.builder().build();
    private final LoginRules rules = new LoginRules();
    private final FixedWindowRateLimiter rateLimiter;
    private final String requiredToken;

    LoginHandler(MockConfig config) {
        this.rateLimiter = new FixedWindowRateLimiter(config.rateLimitPerSecond(), System::currentTimeMillis);
        this.requiredToken = config.requiredToken();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Allow", ALLOW);
            switch (exchange.getRequestMethod()) {
                case "POST" -> send(exchange, login(exchange));
                case "OPTIONS" -> exchange.sendResponseHeaders(200, -1);
                case "HEAD" -> exchange.sendResponseHeaders(405, -1);
                default -> send(exchange, MockResponse.message(405, "Method Not Allowed"));
            }
        }
    }

    private MockResponse login(HttpExchange exchange) throws IOException {
        byte[] raw;
        try (InputStream in = exchange.getRequestBody()) {
            raw = in.readAllBytes();
        }
        simulateWork();
        if (!requiredToken.isEmpty()
                && !("Bearer " + requiredToken).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            return MockResponse.message(401, "Missing or wrong bearer token");
        }
        if (!rateLimiter.tryAcquire()) {
            return MockResponse.message(429, "Too many requests");
        }
        try {
            return rules.evaluate(parse(raw));
        } catch (LoginRules.UnhandledInputException e) {
            return MockResponse.message(500, "Internal Server Error");
        }
    }

    private JsonNode parse(byte[] raw) {
        try {
            return json.readTree(raw);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static void simulateWork() {
        long millis = ThreadLocalRandom.current().nextLong(MIN_WORK.toMillis(), MAX_WORK.toMillis() + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(HttpExchange exchange, MockResponse response) throws IOException {
        byte[] body = json.writeValueAsBytes(response.body());
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** A tiny handler for {@code /health}, used by Docker health checks. */
    static HttpHandler health() {
        ObjectMapper mapper = JsonMapper.builder().build();
        return exchange -> {
            try (exchange) {
                byte[] body = mapper.writeValueAsBytes(Map.of("status", "ok"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        };
    }
}
