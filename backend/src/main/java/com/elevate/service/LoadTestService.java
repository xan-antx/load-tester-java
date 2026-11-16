package com.elevate.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.elevate.analysis.capacity.CapacityPlanValidator;
import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.analysis.probe.SitemapReader;
import com.elevate.analysis.slo.Slo;
import com.elevate.analysis.slo.SloValidator;
import com.elevate.analysis.target.TargetGuard;
import com.elevate.dto.StartRunRequest;
import com.elevate.dto.StartRunResponse;
import com.elevate.exception.BadRequestException;
import com.elevate.exception.OwnershipNotConfirmedException;
import com.elevate.exception.TargetBlockedException;
import com.elevate.model.CapacityPlan;
import com.elevate.model.Expectation;
import com.elevate.model.JobType;
import com.elevate.model.RepeatSeries;
import com.elevate.model.RunCase;
import com.elevate.model.RunSettings;
import com.elevate.model.SplitGroup;
import com.elevate.model.TestRun;

import tools.jackson.databind.JsonNode;

/**
 * Validates start requests, stores the new run(s) and hands them to the {@link RunExecutor}.
 *
 * <p>Every start must confirm ownership ({@code "i_own_target": true}) and pass the target
 * guard. Then, depending on the request, it creates one of four kinds of run: a single job,
 * a split group (above 50 users), a repeat series, or a capacity run.
 */
@Service
public class LoadTestService {

    private final TargetGuard guard;
    private final ActiveRunLimiter limiter;
    private final RunStore store;
    private final RunExecutor executor;
    private final SitemapReader sitemap;
    private final Clock clock;

    /**
     * @param guard    SSRF guard
     * @param limiter  MAX_ACTIVE_RUNS slots
     * @param store    database writes
     * @param executor background execution
     * @param sitemap  sitemap parsing for website runs
     * @param clock    for timestamps
     */
    public LoadTestService(TargetGuard guard, ActiveRunLimiter limiter, RunStore store, RunExecutor executor,
                           SitemapReader sitemap, Clock clock) {
        this.guard = guard;
        this.limiter = limiter;
        this.store = store;
        this.executor = executor;
        this.sitemap = sitemap;
        this.clock = clock;
    }

    /** Starts a load test of an API endpoint with confirmed edge cases. */
    public StartRunResponse startApiTest(StartRunRequest request) {
        List<RunCase> cases = apiCases(request.confirmedCases());
        Target target = checkTarget(request);
        return start(JobType.API, request, target, cases, null);
    }

    /** Starts a load test of website pages (from the sitemap, or the URL's own path). */
    public StartRunResponse startWebsiteTest(StartRunRequest request) {
        Target target = checkTarget(request);
        List<String> paths = request.sitemapRaw() == null || request.sitemapRaw().isBlank()
                ? List.of() : sitemap.extractPaths(request.sitemapRaw());
        if (paths.isEmpty()) {
            paths = List.of(target.path());
        }
        return start(JobType.WEBSITE, request, target, paths.stream().map(RunCase::page).toList(), paths);
    }

    private StartRunResponse start(JobType type, StartRunRequest request, Target target, List<RunCase> cases,
                                   List<String> pathsUsed) {
        RequestHeaders headers = RequestHeaders.clean(request.headers());
        Slo slo = SloValidator.clean(request.slo());
        CapacityPlan plan = capacityPlan(request, slo);
        RunSettings settings = new RunSettings(target.host(), type, request.users(), request.spawnRate(),
                request.durationSeconds(), headers.names());
        limiter.acquire();
        try {
            if (plan != null) {
                RunSettings capacity = new RunSettings(target.host(), type, plan.maxUsers(), request.spawnRate(),
                        plan.totalSeconds(), headers.names());
                PreparedRun run = store.saveJob(new TestRun(newId(), capacity, target.path(), copies(cases), slo, plan,
                        clock.instant()));
                executor.launch(run, headers);
                return StartRunResponse.capacity(run.id(), plan);
            }
            if (request.repeat() > 1) {
                String seriesId = newId();
                store.saveSeries(new RepeatSeries(seriesId, settings, request.repeat(), clock.instant()));
                executor.launchSeries(seriesId, request.repeat(), () -> prepare(settings, target, cases, slo), headers);
                return StartRunResponse.series(seriesId, SplitPlan.isSplit(request.users()), request.repeat(), pathsUsed);
            }
            PreparedRun run = prepare(settings, target, cases, slo);
            executor.launch(run, headers);
            return StartRunResponse.run(run.id(), run.isGroup(), pathsUsed);
        } catch (RuntimeException e) {
            limiter.release();
            throw e;
        }
    }

    /** Stores one run: a single job, or a split group when there are more than 50 users. */
    private PreparedRun prepare(RunSettings settings, Target target, List<RunCase> cases, Slo slo) {
        if (!SplitPlan.isSplit(settings.users())) {
            return store.saveJob(new TestRun(newId(), settings, target.path(), copies(cases), slo, null, clock.instant()));
        }
        List<Integer> shares = SplitPlan.split(settings.users());
        SplitGroup group = new SplitGroup(newId(), settings, shares.size(), slo, clock.instant());
        List<TestRun> children = new ArrayList<>();
        for (int share : shares) {
            children.add(new TestRun(newId(), settings.withUsers(share), target.path(), copies(cases), slo, null,
                    clock.instant()));
        }
        return store.saveGroup(group, children);
    }

    private static CapacityPlan capacityPlan(StartRunRequest request, Slo slo) {
        if ("fixed".equals(request.mode())) {
            return null;
        }
        if (!"capacity".equals(request.mode())) {
            throw new BadRequestException("mode must be \"fixed\" or \"capacity\"");
        }
        if (request.repeat() > 1) {
            throw new BadRequestException("repeat cannot be combined with a capacity run");
        }
        CapacityPlan plan = CapacityPlanValidator.clean(request.capacity());
        CapacityPlanValidator.requireStepSlo(slo);
        CapacityPlanValidator.rampProblem(plan, request.spawnRate()).ifPresent(problem -> {
            throw new BadRequestException(problem);
        });
        return plan;
    }

    /** Ownership, target guard and URL shape: the checks every start makes. */
    private Target checkTarget(StartRunRequest request) {
        if (!request.ownershipConfirmed()) {
            throw new OwnershipNotConfirmedException();
        }
        URI uri;
        try {
            uri = new URI(request.url().strip());
        } catch (URISyntaxException e) {
            throw new BadRequestException("url must be a valid http(s) URL");
        }
        if (uri.getHost() != null) {
            guard.check(uri.toString()).ifPresent(problem -> {
                throw new TargetBlockedException(problem);
            });
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!(scheme.equals("http") || scheme.equals("https")) || uri.getRawAuthority() == null) {
            throw new BadRequestException("url must be a valid http(s) URL");
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        return new Target(scheme + "://" + uri.getRawAuthority(), path);
    }

    /** The confirmed cases of an API start request as stored cases. */
    private static List<RunCase> apiCases(JsonNode confirmed) {
        if (confirmed == null || !confirmed.isArray() || confirmed.isEmpty()) {
            throw new BadRequestException("confirmed_cases (non-empty list) is required");
        }
        List<RunCase> cases = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (JsonNode c : confirmed) {
            JsonNode label = c.get("label");
            if (!c.isObject() || label == null || !label.isString() || label.stringValue().isEmpty()) {
                throw new BadRequestException("each confirmed case must be an object with a label");
            }
            String name = label.stringValue();
            JsonNode expect = c.get("expect");
            Expectation expectation = null;
            if (expect != null && !expect.isNull()) {
                expectation = Expectation.fromWire(expect.isString() ? expect.stringValue() : null).orElseThrow(
                        () -> new BadRequestException("case '" + name + "': expect must be \"accept\" or \"reject\""));
            }
            if (!labels.add(name)) {
                throw new BadRequestException("case '" + name + "' appears twice");
            }
            JsonNode payload = c.get("payload");
            cases.add(RunCase.edgeCase(name, text(c, "category"), text(c, "classified_as"), text(c, "description"),
                    payload == null ? "null" : payload.toString(), expectation));
        }
        return cases;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    private static List<RunCase> copies(List<RunCase> cases) {
        return cases.stream().map(RunCase::copy).toList();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * @param host scheme and authority, e.g. {@code http://127.0.0.1:8000}
     * @param path path (and query) of the URL, {@code /} when empty
     */
    private record Target(String host, String path) {
    }
}
