package com.elevate.service;

import java.util.List;

import com.elevate.analysis.slo.Slo;
import com.elevate.model.CapacityPlan;
import com.elevate.model.JobType;
import com.elevate.model.RunCase;

/**
 * What the engine needs to know about one job, read from the database in one transaction
 * so the engine thread never touches a JPA entity.
 *
 * @param jobId           the job
 * @param jobType         API or website
 * @param targetUrl       scheme and host
 * @param targetPath      path of the API endpoint (API runs)
 * @param cases           what to send
 * @param users           virtual users
 * @param spawnRate       users started per second
 * @param durationSeconds length
 * @param capacityPlan    step plan, or {@code null}
 * @param slo             thresholds, or {@code null}
 */
public record JobPlan(String jobId, JobType jobType, String targetUrl, String targetPath, List<RunCase> cases,
                      int users, int spawnRate, int durationSeconds, CapacityPlan capacityPlan, Slo slo) {

    /** Defensive copy. */
    public JobPlan {
        cases = List.copyOf(cases);
    }
}
