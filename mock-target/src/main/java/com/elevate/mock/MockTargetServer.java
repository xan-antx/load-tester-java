package com.elevate.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

/**
 * A small, controlled login API to load-test against (a Java port of the Python
 * {@code scripts/mock_target.py}). Built on the JDK's {@link HttpServer} with a
 * <b>fixed</b> thread pool, whose size is the number of "workers".
 *
 * <p>Run it with {@code java -jar mock-target-1.0.0-exec.jar}; it listens on
 * {@code http://127.0.0.1:8000/api/login} unless {@code MOCK_HOST}/{@code MOCK_PORT} say otherwise.
 * Tests call {@link #start(MockConfig)} with port 0 and read {@link #port()}.
 */
public final class MockTargetServer implements AutoCloseable {

    /** Pending connections the OS keeps while every worker is busy. */
    private static final int BACKLOG = 1024;

    private final HttpServer server;
    private final ExecutorService workers;
    private final MockConfig config;

    private MockTargetServer(HttpServer server, ExecutorService workers, MockConfig config) {
        this.server = server;
        this.workers = workers;
        this.config = config;
    }

    /**
     * Starts a mock server.
     *
     * @param config where to listen and how to behave
     * @return the running server
     * @throws IOException if the port cannot be bound
     */
    public static MockTargetServer start(MockConfig config) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), BACKLOG);
        ExecutorService workers = Executors.newFixedThreadPool(config.workers());
        server.setExecutor(workers);
        server.createContext("/api/login", new LoginHandler(config));
        server.createContext("/health", LoginHandler.health());
        server.start();
        return new MockTargetServer(server, workers, config);
    }

    /** The port actually bound (useful when started with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    /** The login endpoint's URL, e.g. {@code http://127.0.0.1:8000/api/login}. */
    public String loginUrl() {
        return "http://" + config.host() + ":" + port() + "/api/login";
    }

    /** Stops accepting requests and stops the worker threads. */
    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }

    /**
     * Entry point.
     *
     * @param args ignored; configuration comes from {@code MOCK_*} environment variables
     * @throws IOException if the port cannot be bound
     */
    public static void main(String[] args) throws IOException {
        MockConfig config = MockConfig.fromEnvironment(System.getenv());
        MockTargetServer mock = start(config);
        Runtime.getRuntime().addShutdownHook(new Thread(mock::close));
        System.out.printf("Mock target on %s (rate limit %d req/s, %d workers%s)%n",
                mock.loginUrl(), config.rateLimitPerSecond(), config.workers(),
                config.requiredToken().isEmpty() ? "" : ", bearer token required");
    }
}
