package com.elevate.service;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.springframework.stereotype.Component;

import com.elevate.analysis.capacity.CapacityEvaluator;
import com.elevate.analysis.headers.RequestHeaders;
import com.elevate.config.AppProperties;
import com.elevate.engine.EngineSpec;
import com.elevate.engine.LoadProfile;
import com.elevate.engine.RequestTemplate;
import com.elevate.engine.ThinkTime;
import com.elevate.model.CapacityPlan;
import com.elevate.model.JobType;
import com.elevate.model.RunCase;

/** Turns a stored job into an engine specification (a Factory). */
@Component
public class EngineSpecFactory {

    private final AppProperties.Engine settings;

    /** @param properties think time and request timeout */
    public EngineSpecFactory(AppProperties properties) {
        this.settings = properties.engine();
    }

    /**
     * @param plan    the job
     * @param headers extra request headers (values in memory only)
     * @return the specification
     */
    public EngineSpec create(JobPlan plan, RequestHeaders headers) {
        EngineSpec.Builder builder = EngineSpec.builder()
                .templates(templates(plan))
                .headers(headers)
                .thinkTime(new ThinkTime(settings.thinkTimeMin(), settings.thinkTimeMax()))
                .requestTimeout(settings.requestTimeout());
        CapacityPlan capacity = plan.capacityPlan();
        if (capacity == null) {
            return builder.profile(new LoadProfile.Fixed(plan.users(), plan.spawnRate(),
                    Duration.ofSeconds(plan.durationSeconds()))).build();
        }
        builder.profile(new LoadProfile.Steps(capacity.steps(), Duration.ofSeconds(capacity.stepDurationSeconds()),
                plan.spawnRate()));
        if (Boolean.TRUE.equals(capacity.earlyStop()) && plan.slo() != null) {
            builder.stepJudge(step -> CapacityEvaluator.breaksSlo(step, plan.slo()));
        }
        return builder.build();
    }

    private static List<RequestTemplate> templates(JobPlan plan) {
        return plan.cases().stream().map(c -> template(plan, c)).toList();
    }

    private static RequestTemplate template(JobPlan plan, RunCase c) {
        if (plan.jobType() == JobType.WEBSITE) {
            return RequestTemplate.get(c.getLabel(), URI.create(plan.targetUrl() + c.getLabel()));
        }
        return RequestTemplate.post(c.getLabel(), URI.create(plan.targetUrl() + plan.targetPath()), c.getPayloadJson(),
                c.getExpect());
    }
}
