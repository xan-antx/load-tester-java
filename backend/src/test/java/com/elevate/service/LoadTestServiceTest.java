package com.elevate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.elevate.analysis.probe.SitemapReader;
import com.elevate.analysis.target.TargetGuard;
import com.elevate.dto.StartRunRequest;
import com.elevate.dto.StartRunResponse;
import com.elevate.exception.BadRequestException;
import com.elevate.exception.OwnershipNotConfirmedException;
import com.elevate.exception.TargetBlockedException;
import com.elevate.exception.TooManyActiveRunsException;
import com.elevate.model.Expectation;
import com.elevate.model.RepeatSeries;
import com.elevate.model.RunMode;
import com.elevate.model.SplitGroup;
import com.elevate.model.TestRun;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class LoadTestServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String URL = "http://127.0.0.1:8000/api/login";

    @Mock
    TargetGuard guard;
    @Mock
    ActiveRunLimiter limiter;
    @Mock
    RunStore store;
    @Mock
    RunExecutor executor;
    @Mock
    SitemapReader sitemap;

    private LoadTestService service;

    @BeforeEach
    void setUp() {
        service = new LoadTestService(guard, limiter, store, executor, sitemap,
                Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC));
    }

    private static JsonNode json(String text) {
        return text == null ? null : JSON.readTree(text);
    }

    private static final String CASES = "[{\"label\":\"valid_sample\",\"category\":\"valid_input\",\"payload\":{\"u\":1},"
            + "\"expect\":\"accept\"},{\"label\":\"null_u\",\"payload\":{\"u\":null},\"expect\":\"reject\"}]";

    private static StartRunRequest request(String url, String cases, Boolean own, Integer users, Integer repeat,
                                           String slo, String mode, String capacity) {
        return new StartRunRequest(url, json(cases), own == null ? null : JSON.getNodeFactory().booleanNode(own),
                users, 20, 10, json("{\"Authorization\":\"Bearer s\"}"), repeat, json(slo), mode, json(capacity), null);
    }

    private static StartRunRequest simple(Integer users) {
        return request(URL, CASES, true, users, null, null, null, null);
    }

    @Test
    void singleRunIsStoredAndLaunched() {
        when(store.saveJob(any())).thenAnswer(inv -> new PreparedRun(((TestRun) inv.getArgument(0)).getId(), List.of()));
        StartRunResponse response = service.startApiTest(simple(15));

        ArgumentCaptor<TestRun> run = ArgumentCaptor.forClass(TestRun.class);
        verify(store).saveJob(run.capture());
        assertThat(run.getValue().getTargetUrl()).isEqualTo("http://127.0.0.1:8000");
        assertThat(run.getValue().getTargetPath()).isEqualTo("/api/login");
        assertThat(run.getValue().getHeaderNames()).containsExactly("Authorization");
        assertThat(run.getValue().getCases()).extracting(c -> c.getExpect())
                .containsExactly(Expectation.ACCEPT, Expectation.REJECT);
        assertThat(run.getValue().getCases().getFirst().getPayloadJson()).isEqualTo("{\"u\":1}");
        verify(limiter).acquire();
        verify(executor).launch(any(), any());
        assertThat(response.jobId()).isEqualTo(run.getValue().getId());
        assertThat(response.isGroup()).isFalse();
    }

    @Test
    void moreThanFiftyUsersAreSplit() {
        when(store.saveGroup(any(), anyList())).thenAnswer(inv -> new PreparedRun(((SplitGroup) inv.getArgument(0)).getId(),
                ((List<TestRun>) inv.getArgument(1)).stream().map(TestRun::getId).toList()));
        StartRunResponse response = service.startApiTest(simple(120));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TestRun>> children = ArgumentCaptor.forClass(List.class);
        verify(store).saveGroup(any(), children.capture());
        assertThat(children.getValue()).extracting(TestRun::getUsers).containsExactly(40, 40, 40);
        assertThat(response.isGroup()).isTrue();
    }

    @Test
    void repeatStartsASeries() {
        StartRunResponse response = service.startApiTest(request(URL, CASES, true, 5, 3, null, null, null));
        verify(store).saveSeries(any(RepeatSeries.class));
        verify(executor).launchSeries(anyString(), eq(3), any(), any());
        assertThat(response.isSeries()).isTrue();
        assertThat(response.seriesId()).isEqualTo(response.jobId());
    }

    @Test
    void capacityRunIsOneJobWithThePlan() {
        when(store.saveJob(any())).thenAnswer(inv -> new PreparedRun(((TestRun) inv.getArgument(0)).getId(), List.of()));
        StartRunResponse response = service.startApiTest(request(URL, CASES, true, 5, null, "{\"p95_ms\":500}", "capacity",
                "{\"start_users\":20,\"step_users\":20,\"step_duration_seconds\":12,\"max_users\":60}"));
        ArgumentCaptor<TestRun> run = ArgumentCaptor.forClass(TestRun.class);
        verify(store).saveJob(run.capture());
        assertThat(run.getValue().getMode()).isEqualTo(RunMode.CAPACITY);
        assertThat(run.getValue().getUsers()).isEqualTo(60);
        assertThat(run.getValue().getDurationSeconds()).isEqualTo(36);
        assertThat(response.capacityPlan().steps()).containsExactly(20, 40, 60);
    }

    @Test
    void capacityNeedsAnSloAndNoRepeat() {
        String plan = "{\"start_users\":20,\"step_users\":20,\"step_duration_seconds\":12,\"max_users\":60}";
        assertThatThrownBy(() -> service.startApiTest(request(URL, CASES, true, 5, null, null, "capacity", plan)))
                .hasMessageStartingWith("A capacity run needs an SLO");
        assertThatThrownBy(() -> service.startApiTest(request(URL, CASES, true, 5, 2, "{\"p95_ms\":5}", "capacity", plan)))
                .hasMessage("repeat cannot be combined with a capacity run");
        assertThatThrownBy(() -> service.startApiTest(request(URL, CASES, true, 5, null, null, "turbo", null)))
                .hasMessage("mode must be \"fixed\" or \"capacity\"");
        verifyNoInteractions(limiter);
    }

    @Test
    void ownershipMustBeConfirmed() {
        assertThatThrownBy(() -> service.startApiTest(request(URL, CASES, null, 5, null, null, null, null)))
                .isInstanceOf(OwnershipNotConfirmedException.class);
        assertThatThrownBy(() -> service.startApiTest(request(URL, CASES, false, 5, null, null, null, null)))
                .isInstanceOf(OwnershipNotConfirmedException.class);
        verifyNoInteractions(store, executor, limiter);
    }

    @Test
    void blockedTargetsAreRefused() {
        when(guard.check(anyString())).thenReturn(Optional.of("'x' resolves to 10.0.0.1, a private address."));
        assertThatThrownBy(() -> service.startApiTest(simple(5))).isInstanceOf(TargetBlockedException.class)
                .hasMessageStartingWith("Target not allowed: ");
        verifyNoInteractions(store);
    }

    @Test
    void confirmedCasesAreChecked() {
        assertThatThrownBy(() -> service.startApiTest(request(URL, "[]", true, 5, null, null, null, null)))
                .hasMessage("confirmed_cases (non-empty list) is required");
        assertThatThrownBy(() -> service.startApiTest(request(URL, "[{\"label\":\"a\",\"expect\":\"maybe\"}]", true, 5,
                null, null, null, null))).hasMessage("case 'a': expect must be \"accept\" or \"reject\"");
        assertThatThrownBy(() -> service.startApiTest(request(URL, "[{\"label\":\"a\"},{\"label\":\"a\"}]", true, 5,
                null, null, null, null))).hasMessage("case 'a' appears twice");
        assertThatThrownBy(() -> service.startApiTest(request(URL, "[1]", true, 5, null, null, null, null)))
                .hasMessage("each confirmed case must be an object with a label");
    }

    @Test
    void urlMustBeHttp() {
        assertThatThrownBy(() -> service.startApiTest(request("ftp://x/y", CASES, true, 5, null, null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("url must be a valid http(s) URL");
        assertThatThrownBy(() -> service.startApiTest(request("http://bad host/", CASES, true, 5, null, null, null, null)))
                .hasMessage("url must be a valid http(s) URL");
    }

    @Test
    void busyServerGives429AndStoresNothing() {
        doThrow(new TooManyActiveRunsException(1, 1)).when(limiter).acquire();
        assertThatThrownBy(() -> service.startApiTest(simple(5))).isInstanceOf(TooManyActiveRunsException.class);
        verifyNoInteractions(store, executor);
    }

    @Test
    void theSlotIsGivenBackWhenStoringFails() {
        when(store.saveJob(any())).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> service.startApiTest(simple(5))).hasMessage("db down");
        verify(limiter).release();
        verify(executor, never()).launch(any(), any());
    }

    @Test
    void websiteRunsUseTheSitemapOrTheUrlPath() {
        when(store.saveJob(any())).thenAnswer(inv -> new PreparedRun(((TestRun) inv.getArgument(0)).getId(), List.of()));
        when(sitemap.extractPaths("<xml/>")).thenReturn(List.of("/", "/about"));
        StartRunRequest withSitemap = new StartRunRequest("http://site.test/home?x=1", null,
                JSON.getNodeFactory().booleanNode(true), 3, 1, 5, null, null, null, null, null, "<xml/>");
        assertThat(service.startWebsiteTest(withSitemap).pathsUsed()).containsExactly("/", "/about");
        StartRunRequest without = new StartRunRequest("http://site.test/home?x=1", null,
                JSON.getNodeFactory().booleanNode(true), 3, 1, 5, null, null, null, null, null, null);
        assertThat(service.startWebsiteTest(without).pathsUsed()).containsExactly("/home?x=1");
    }

    @Test
    void defaultsMatchPython() {
        StartRunRequest r = new StartRunRequest(URL, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(List.of(r.users(), r.spawnRate(), r.durationSeconds(), r.repeat())).containsExactly(5, 1, 30, 1);
        assertThat(r.mode()).isEqualTo("fixed");
        assertThat(r.ownershipConfirmed()).isFalse();
    }
}
