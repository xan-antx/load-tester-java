package com.elevate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** A tiny HTTP server on a free port with canned answers per path, for tests. */
public final class StubServer implements AutoCloseable {

    /**
     * One canned answer.
     *
     * @param status      HTTP status
     * @param contentType Content-Type, or {@code null}
     * @param body        body text
     * @param headers     extra response headers
     */
    public record Answer(int status, String contentType, String body, Map<String, String> headers) {
        /** A JSON answer. */
        public static Answer json(int status, String body) {
            return new Answer(status, "application/json", body, Map.of());
        }
    }

    /** Decides the answer for a method. */
    @FunctionalInterface
    public interface Route {
        /** @return the answer to {@code method} */
        Answer answer(String method);
    }

    private final HttpServer server;

    /** @param routes path → route */
    public StubServer(Map<String, Route> routes) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 100);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        routes.forEach((path, route) -> server.createContext(path, exchange -> respond(exchange, route)));
        server.start();
    }

    private static void respond(HttpExchange exchange, Route route) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            Answer a = route.answer(exchange.getRequestMethod());
            if (a.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", a.contentType());
            }
            a.headers().forEach(exchange.getResponseHeaders()::set);
            byte[] body = a.body().getBytes(StandardCharsets.UTF_8);
            boolean noBody = exchange.getRequestMethod().equals("HEAD") || body.length == 0;
            exchange.sendResponseHeaders(a.status(), noBody ? -1 : body.length);
            if (!noBody) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    /** {@code http://127.0.0.1:<port><path>}. */
    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
