package com.elevate.mock;

import java.util.Map;

/**
 * Settings of the mock target, read from environment variables with the same names
 * as the Python mock ({@code MOCK_HOST}, {@code MOCK_PORT}, {@code MOCK_RATE_LIMIT},
 * {@code MOCK_WORKERS}, {@code MOCK_REQUIRE_TOKEN}).
 *
 * @param host               address to listen on ({@code 0.0.0.0} inside Docker)
 * @param port               TCP port; {@code 0} picks a free port (used by tests)
 * @param rateLimitPerSecond requests allowed per one-second window before answering 429
 * @param workers            requests processed at the same time; the rest wait in line
 * @param requiredToken      bearer token every request must carry, or empty for none
 */
public record MockConfig(String host, int port, int rateLimitPerSecond, int workers, String requiredToken) {

    /** Defaults of the Python mock: 127.0.0.1:8000, 40 requests/s, 8 workers, no token. */
    public static MockConfig defaults() {
        return new MockConfig("127.0.0.1", 8000, 40, 8, "");
    }

    /**
     * Builds the configuration from environment variables, falling back to {@link #defaults()}.
     *
     * @param env the environment, usually {@link System#getenv()}
     * @return the configuration
     */
    public static MockConfig fromEnvironment(Map<String, String> env) {
        MockConfig d = defaults();
        return new MockConfig(
                env.getOrDefault("MOCK_HOST", d.host()),
                intOf(env, "MOCK_PORT", d.port()),
                intOf(env, "MOCK_RATE_LIMIT", d.rateLimitPerSecond()),
                intOf(env, "MOCK_WORKERS", d.workers()),
                env.getOrDefault("MOCK_REQUIRE_TOKEN", d.requiredToken()));
    }

    /** A copy listening on another port (tests use port 0). */
    public MockConfig withPort(int newPort) {
        return new MockConfig(host, newPort, rateLimitPerSecond, workers, requiredToken);
    }

    /** A copy with another rate limit. */
    public MockConfig withRateLimit(int newLimit) {
        return new MockConfig(host, port, newLimit, workers, requiredToken);
    }

    private static int intOf(Map<String, String> env, String name, int fallback) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, got '" + raw + "'", e);
        }
    }
}
