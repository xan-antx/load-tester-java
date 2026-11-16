package com.elevate.dto;

import java.util.List;

import com.elevate.model.CapacityPlan;
import com.elevate.model.RunMode;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 202 body of the start endpoints. Fields that do not apply to the kind of run are left out.
 *
 * @param jobId        id to poll (a series id for a series)
 * @param seriesId     series only
 * @param status       always {@code started}
 * @param isGroup      the run is split into several engine instances
 * @param isSeries     series only
 * @param repeat       series only
 * @param mode         capacity runs only
 * @param capacityPlan capacity runs only
 * @param pathsUsed    website runs only
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartRunResponse(String jobId, String seriesId, String status, boolean isGroup, Boolean isSeries,
                               Integer repeat, RunMode mode, CapacityPlan capacityPlan, List<String> pathsUsed) {

    /** A single run or split group. */
    public static StartRunResponse run(String id, boolean isGroup, List<String> pathsUsed) {
        return new StartRunResponse(id, null, "started", isGroup, null, null, null, null, pathsUsed);
    }

    /** A repeat series. */
    public static StartRunResponse series(String id, boolean isGroup, int repeat, List<String> pathsUsed) {
        return new StartRunResponse(id, id, "started", isGroup, true, repeat, null, null, pathsUsed);
    }

    /** A capacity run. */
    public static StartRunResponse capacity(String id, CapacityPlan plan) {
        return new StartRunResponse(id, null, "started", false, null, null, RunMode.CAPACITY, plan, null);
    }
}
