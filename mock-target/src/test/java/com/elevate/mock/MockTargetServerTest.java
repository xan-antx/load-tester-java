package com.elevate.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MockTargetServerTest {

    private MockTargetServer mock;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void start() throws Exception {
        mock = MockTargetServer.start(MockConfig.defaults().withPort(0));
    }

    @AfterEach
    void stop() {
        mock.close();
    }

    private HttpResponse<String> send(String method, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(mock.loginUrl()))
                .header("Content-Type", "application/json")
                .method(method, publisher).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void loginWorksOverHttp() throws Exception {
        HttpResponse<String> r = send("POST", "{\"username\":\"demo\",\"password\":\"demo123\"}");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("mock-access-token");
        assertThat(r.headers().firstValue("Content-Type")).hasValue("application/json");
    }

    @Test
    void crashInputGives500() throws Exception {
        assertThat(send("POST", "{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":\"x\"}")
                .statusCode()).isEqualTo(500);
    }

    @Test
    void onlyPostIsAllowed() throws Exception {
        HttpResponse<String> get = send("GET", null);
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(get.headers().firstValue("Allow")).hasValue("OPTIONS, POST");
        assertThat(send("HEAD", null).statusCode()).isEqualTo(405);
        assertThat(send("OPTIONS", null).statusCode()).isEqualTo(200);
    }

    @Test
    void healthEndpoint() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mock.port() + "/health")).build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).body()).contains("ok");
    }

    @Test
    void rateLimiterAllowsTheLimitPerSecondThenRefuses() {
        AtomicLong now = new AtomicLong(5_000);
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2, now::get);
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        now.set(6_000);
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    void configIsReadFromTheEnvironment() {
        MockConfig config = MockConfig.fromEnvironment(Map.of("MOCK_PORT", "9001", "MOCK_RATE_LIMIT", "5"));
        assertThat(config.port()).isEqualTo(9001);
        assertThat(config.rateLimitPerSecond()).isEqualTo(5);
        assertThat(config.workers()).isEqualTo(8);
    }
}
