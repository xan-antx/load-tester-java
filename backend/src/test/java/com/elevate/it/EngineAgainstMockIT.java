package com.elevate.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.elevate.analysis.edgecase.EdgeCase;
import com.elevate.analysis.edgecase.EdgeCaseSuite;
import com.elevate.analysis.outcome.Outcome;
import com.elevate.analysis.stats.CaseMeasurement;
import com.elevate.analysis.stats.FailureCount;
import com.elevate.engine.EngineResult;
import com.elevate.engine.EngineSpec;
import com.elevate.engine.LoadEngine;
import com.elevate.engine.LoadProfile;
import com.elevate.engine.RequestTemplate;
import com.elevate.engine.ThinkTime;
import com.elevate.mock.MockConfig;
import com.elevate.mock.MockTargetServer;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The engine, on its own, against the real Java mock target: a few seconds of load must find
 * exactly the mock's deliberate bugs and nothing else.
 */
class EngineAgainstMockIT {

    private static MockTargetServer mock;

    @BeforeAll
    static void startMock() throws Exception {
        mock = MockTargetServer.start(MockConfig.defaults().withPort(0).withRateLimit(100_000));
    }

    @AfterAll
    static void stopMock() {
        mock.close();
    }

    @Test
    void findsTheThreeDeliberateBugsAndNothingElse() {
        ObjectNode sample = (ObjectNode) JsonMapper.builder().build()
                .readTree("{\"username\":\"demo\",\"password\":\"demo123\",\"expiresInMins\":30}");
        URI login = URI.create(mock.loginUrl());
        List<RequestTemplate> templates = EdgeCaseSuite.standard().generate(sample).stream()
                .map((EdgeCase c) -> RequestTemplate.post(c.label(), login, c.payload().toString(), c.expect()))
                .toList();
        EngineSpec spec = EngineSpec.builder().templates(templates)
                .profile(new LoadProfile.Fixed(20, 20, Duration.ofSeconds(5)))
                .thinkTime(new ThinkTime(Duration.ofMillis(20), Duration.ofMillis(60)))
                .requestTimeout(Duration.ofSeconds(10))
                .build();

        EngineResult result = new LoadEngine(spec).run();

        assertThat(result.results().cases()).as("every case was sent").allSatisfy(c -> assertThat(c.requests()).isPositive());
        Map<String, Set<Outcome>> failing = result.results().failures().stream().collect(Collectors.groupingBy(
                FailureCount::caseName, Collectors.mapping(FailureCount::outcome, Collectors.toSet())));
        assertThat(failing).containsOnlyKeys("wrong_type_expiresInMins", "very_large_expiresInMins",
                "very_long_string_username", "missing_expiresInMins", "null_expiresInMins");
        assertThat(failing.get("wrong_type_expiresInMins")).containsOnly(Outcome.SERVER_ERROR);
        assertThat(failing.get("very_large_expiresInMins")).containsOnly(Outcome.SERVER_ERROR);
        assertThat(failing.get("very_long_string_username")).containsOnly(Outcome.ACCEPTED_INVALID_INPUT);
        // expiresInMins is optional on the mock, so with the default expectation ("reject")
        // these two are reported as accepted invalid input — the reason the UI lets you flip them.
        assertThat(failing.get("missing_expiresInMins")).containsOnly(Outcome.ACCEPTED_INVALID_INPUT);

        CaseMeasurement valid = result.results().cases().getFirst();
        assertThat(valid.failures()).isZero();
        assertThat(valid.histogram().getValueAtPercentile(50)).as("the mock spends at least 50 ms per request")
                .isGreaterThanOrEqualTo(50_000L);
    }
}
