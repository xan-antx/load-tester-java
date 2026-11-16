package com.elevate.dto;

import tools.jackson.databind.JsonNode;

/**
 * Body of {@code /api/compare-jobs}: either {@code job_ids} (2–4 runs) or the older
 * {@code job_id_a} / {@code job_id_b} pair.
 *
 * @param jobIds run ids, as sent
 * @param jobIdA first run (older form)
 * @param jobIdB second run (older form)
 */
public record CompareRequest(JsonNode jobIds, String jobIdA, String jobIdB) {
}
