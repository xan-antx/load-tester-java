package com.elevate.queue;

import java.util.List;

import com.elevate.model.Expectation;
import com.elevate.model.JobType;
import com.elevate.model.RunCase;
import com.elevate.service.JobPlan;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One job on the queue, with the same JSON keys as the Python version's SQS payload:
 * {@code job_id, job_type, target_url, users, spawn_rate, duration_seconds} plus
 * {@code target_path} and {@code cases} for API jobs or {@code paths} for website jobs.
 *
 * @param jobId           the stored job the worker runs
 * @param jobType         {@code api} or {@code website}
 * @param targetUrl       scheme and host of the target
 * @param users           virtual users
 * @param spawnRate       users started per second
 * @param durationSeconds length of the run
 * @param targetPath      API jobs: the endpoint path
 * @param cases           API jobs: the confirmed cases
 * @param paths           website jobs: the pages
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobMessage(String jobId, String jobType, String targetUrl, int users, int spawnRate, int durationSeconds,
                         String targetPath, List<QueuedCase> cases, List<String> paths) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * One confirmed case of an API job.
     *
     * @param label        label
     * @param category     category
     * @param classifiedAs custom cases: the category the diff matched
     * @param description  what was changed
     * @param payload      the JSON body
     * @param expect       {@code accept}, {@code reject} or {@code null}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueuedCase(String label, String category, String classifiedAs, String description, JsonNode payload,
                             Expectation expect) {
    }

    /** The message for a stored job. */
    public static JobMessage of(JobPlan plan) {
        String type = plan.jobType().wire();
        if (plan.jobType() == JobType.WEBSITE) {
            return new JobMessage(plan.jobId(), type, plan.targetUrl(), plan.users(), plan.spawnRate(),
                    plan.durationSeconds(), null, null, plan.cases().stream().map(RunCase::getLabel).toList());
        }
        List<QueuedCase> cases = plan.cases().stream()
                .map(c -> new QueuedCase(c.getLabel(), c.getCategory(), c.getClassifiedAs(), c.getDescription(),
                        JSON.readTree(c.getPayloadJson()), c.getExpect()))
                .toList();
        return new JobMessage(plan.jobId(), type, plan.targetUrl(), plan.users(), plan.spawnRate(),
                plan.durationSeconds(), plan.targetPath(), cases, null);
    }

    /**
     * What the worker runs: the message turned back into a job plan (no capacity plan or
     * SLO — capacity runs are refused in queue mode, and the verdict is computed on read).
     */
    public JobPlan toPlan() {
        boolean website = JobType.WEBSITE.wire().equals(jobType);
        List<RunCase> runCases = website
                ? paths.stream().map(RunCase::page).toList()
                : cases.stream().map(c -> RunCase.edgeCase(c.label(), c.category(), c.classifiedAs(), c.description(),
                        c.payload() == null ? "null" : c.payload().toString(), c.expect())).toList();
        return new JobPlan(jobId, website ? JobType.WEBSITE : JobType.API, targetUrl, targetPath, runCases, users,
                spawnRate, durationSeconds, null, null);
    }
}
