# API contract

This is the specification the Java backend implements. It was written by reading
every route in the Python version (`reference-python/code/app.py`) and every
`fetch` call in its React frontend (`frontend/src/App.jsx`,
`frontend/src/components/AppHeader.jsx`). Field names are **identical** to the
Python version (snake_case), so the frontend works unchanged.

Conventions

- All bodies are JSON (`Content-Type: application/json`), except the Markdown report.
- Every error body has an `error` string; some add extra fields (listed per endpoint).
- Unknown request fields are ignored. A body that is not valid JSON gives
  `400 {"error": "request body must be valid JSON"}`.
- Unknown paths: `404 {"error": "not_found"}`. Unexpected server faults:
  `500 {"error": "internal_server_error", "detail": "..."}`.
- Timestamps (`created_at`, `completed_at`) are UTC in SQLite's format
  `"2026-11-16 10:15:00"` (the frontend appends `Z` itself).
- "Run id" means a single job id, a split group id or a repeat series id (UUIDs).
- Results are returned in Locust's CSV formats (`stats_csv`, `failures_csv`,
  `history_csv`), because the frontend parses them. The Java engine produces
  them itself from its own measurements (see "CSV formats" at the end).

Limits (same as Python): users 1–1000, spawn_rate 1–20, duration_seconds 1–300,
repeat 1–5. Runs above 50 users are split into ⌈users/50⌉ engine instances (at
most 10) that run in parallel.

---

## Health

### `GET /api/health` and `GET /health`
`200 {"status": "ok"}`

---

## Target analysis

### `POST /api/sanity-check`
Request: `{"url": "https://host/path"}`

| Status | Body |
|---|---|
| 200 | `{"valid_format": true, "reachable": bool, "status_code": int\|null, "final_url": str\|null, "headers": {name: value}, "checked_with_method": "HEAD"\|"GET"\|"OPTIONS", "attempts": [Attempt], "method_not_allowed"?: true, "allowed_methods"?: str\|null, "error"?: str, "blocked"?: true}` |
| 400 | `{"error": "url is required"}` |
| 400 | malformed URL: `{"valid_format": false, "reachable": false, "error": "Malformed URL"}` |
| 400 | guard refused: `{"error": "Target not allowed: <reason>", "blocked_target": true}` |

`Attempt = {"method", "status_code", "content_type", "allow"}`.

### `POST /api/analyze`
Request: `{"url": str, "headers"?: {name: value}}`

| Status | Body |
|---|---|
| 200 | `{"sanity": Sanity, "type_detection": {"classification": "api"\|"website"\|"unknown", "confidence": number, "signals": [[name, kind, weight], ...]}, "sitemap"?: {"found": bool, "sitemap_url": str, "raw"?: str, "error"?: "unreachable"}}` (`sitemap` only for websites) |
| 400 | `{"error": "url is required"}` · header validation message · `{"error": "Target not allowed: ...", "blocked_target": true}` |
| 400 | not reachable / malformed: `{"sanity": Sanity}`; a redirect to a blocked host adds `"error"` and `"blocked_target": true` |

Header validation messages: `headers must be an object of name: value pairs`,
`at most 20 headers are allowed`, `a header name may only contain letters, digits and - _ . (no spaces)`,
`the value of header '<name>' must be text`, `... must be a single line`, `... is too long`.
(Java adds: `header '<name>' is set by the HTTP client and cannot be overridden` for
`Host`, `Connection`, `Content-Length`, `Expect`, `Upgrade`.)

---

## Edge cases

`Case = {"label", "category", "description", "payload", "expect": "accept"|"reject"}`;
categories: `valid_input`, `missing_field`, `null_value`, `type_mismatch`,
`boundary_value`, `known_attack`, `custom` (custom cases add `"classified_as"`).

### `POST /api/generate-edge-cases`
Request: `{"sample_input": {...}}`
- `200 {"total_cases": int, "cases": [Case]}`
- `400 {"error": "sample_input (object) is required"}`

### `POST /api/validate-custom-case`
Request: `{"sample_input": {...}, "payload": any, "label": str}`
- `200 {"valid": bool, "reason": str|null, "category": str|null, "description": str|null, "changes": [Change]}`
  (`valid: false` is still 200)
- `400 {"error": "sample_input (object) is required"}`

`Change = {"field", "kind": "removed"|"set_null"|"type_change"|"value_change"|"added", "from"?, "to"?}`

### `POST /api/confirm-selection`
Request: `{"sample_input": {...}, "selected_labels": [str], "custom_cases"?: [{"label", "payload", "expect"?}], "expectations"?: {label: "accept"|"reject"}}`
- `200 {"confirmed_count": int, "confirmed_cases": [Case]}`
- `400` with one of: `sample_input (object) is required`, `custom_cases must be a list`,
  `selected_labels (list) is required`, `expectations must map case labels to "accept" or "reject"`,
  `Unknown labels: [...]`, `each custom case must be an object`, `Custom case '<label>': <reason>`,
  `Custom case '<label>': label used twice`, `Custom case '<label>': expect must be "accept" or "reject"`,
  `No cases matched the selected labels`

---

## Starting, polling and stopping runs

### `POST /api/start-load-test` (API targets)
Request:
```json
{
  "url": "http://127.0.0.1:8000/api/login",
  "confirmed_cases": [Case],
  "i_own_target": true,
  "users": 5, "spawn_rate": 1, "duration_seconds": 30,
  "headers": {"Authorization": "Bearer ..."},
  "repeat": 1,
  "slo": {"p95_ms": 500, "avg_ms": null, "max_server_error_rate_pct": 1,
          "max_unexpected_outcome_rate_pct": null, "min_rps": null},
  "mode": "fixed",
  "capacity": {"start_users": 20, "step_users": 20, "step_duration_seconds": 12,
               "max_users": 200, "early_stop": true}
}
```
Defaults: users 5, spawn_rate 1, duration_seconds 30, repeat 1, mode `fixed`.

| Status | Body |
|---|---|
| 202 | fixed: `{"job_id", "status": "started", "is_group": bool}` |
| 202 | repeat > 1: `{"job_id": seriesId, "series_id", "status": "started", "is_group", "is_series": true, "repeat"}` |
| 202 | capacity: `{"job_id", "status": "started", "is_group": false, "mode": "capacity", "capacity_plan": Plan}` |
| 400 | `url (string) is required`, `confirmed_cases (non-empty list) is required`, `case '<label>': expect must be "accept" or "reject"`, limits (`users must be between 1 and 1000`, …), header messages, `repeat must be between 1 and 5`, SLO messages, `url must be a valid http(s) URL`, capacity messages |
| 400 | `{"error": "Confirm that you own this target or have permission to load-test it (send \"i_own_target\": true).", "missing_confirmation": "i_own_target"}` |
| 400 | `{"error": "Target not allowed: ...", "blocked_target": true}` |
| 429 | `{"error": "A load test is already running, and this server runs at most 1 at a time. Wait for it to finish (or stop it), then start again.", "busy": true, "active_runs": int, "max_active_runs": int}` |

SLO messages: `slo must be an object of thresholds`, `unknown SLO threshold(s): a, b`,
`slo.<key> must be a number`, `slo.<key> must be between <low> and <high> <unit>`.
Ranges: `p95_ms`, `avg_ms` 1–600000 ms; `max_server_error_rate_pct`,
`max_unexpected_outcome_rate_pct` 0–100 %; `min_rps` 0–100000 req/s.

Capacity messages: `mode must be "fixed" or "capacity"`, `repeat cannot be combined with a capacity run`,
`capacity must be an object`, `capacity.<key> must be a whole number`, `capacity.<key> must be between <low> and <high>`,
`capacity.max_users must be at least start_users`, `capacity.early_stop must be true or false`,
`<n> steps x <s> s = <t> s; a capacity run may last at most 300 s (use fewer or shorter steps)`,
`A capacity run needs an SLO to judge each step: ...`, and the spawn-rate check
`spawn_rate <r>/s needs <x> s to add <n> users, more than half of a <s> s step; ...`.
Step duration 5–300 s. `Plan` = the validated capacity object plus `"steps": [users...]` and `"total_seconds"`.

### `POST /api/start-website-load-test` (websites)
Same as above without `confirmed_cases`, plus optional `"sitemap_raw": "<xml>"`.
Pages come from the sitemap (at most 20, assets filtered out), else the URL's path.
Responses add `"paths_used": ["/", "/about"]` (fixed and series).

### `GET /api/load-test-status/{id}`
`404 {"error": "job_id not found"}` when nothing has that id. Otherwise 200 with one of:

**Single job**
```
job_id, target_url, users, spawn_rate, duration_seconds, status, error, group_id,
created_at, completed_at, is_baseline, header_names: [str],
stats_csv, failures_csv, history_csv,            (null until results exist)
case_expectations: {label: "accept"|"reject"}|null,
slo: {...}|null, mode: "fixed"|"capacity", capacity_plan: Plan|null,
failure_summary: [FailureGroup], problem_count: int, problem_rate: number|null,
outcome_summary: OutcomeSummary|null, slo_verdict: SloVerdict|null, capacity: Capacity|null
```

**Split group** (users > 50): the same analysis fields, with `group_id`, `job_type`,
`child_count`, `aggregated_stats_csv`, `aggregated_failures_csv`,
`aggregated_history_csv` and `child_jobs: [job]` (the frontend detects a group by
`child_jobs` being an array). Java addition: `"p95_exact": true` — the merged
percentiles are exact (see below).

**Repeat series**
```
series_id, target_url, job_type, users, spawn_rate, duration_seconds, repeat_count,
run_ids: [id], status, error, header_names, created_at, completed_at,
runs: [{"id", "status", "is_group", "slo_verdict": "PASS"|"FAIL"|null, "problem_rate"}],
series_summary: SeriesSummary|null     (once the series has stopped)
```

Statuses: `queued`, `running`, `completed`, `failed`, `cancelled`, `interrupted`
(the frontend also knows `timeout`, which the Java engine never needs: it stops itself).

`FailureGroup = {"code": int|null, "reason", "kind", "meaning", "total", "cases": [{"case", "occurrences"}]}`
with kind one of `accepted_invalid_input`, `rejected_valid_input`, `server_error`,
`rate_limited`, `connection`, `rejected`, `other`.

`OutcomeSummary = {total_requests, handled_correctly, handled_correctly_pct, accepted_invalid_input,
rejected_valid_input, server_error, connection, rate_limited, other, unexpected_outcome_count,
unexpected_outcome_rate_pct, server_error_rate_pct}` (only when cases carry expectations).

`SloVerdict = {"verdict": "PASS"|"FAIL"|null, "checks": [{"key", "label", "unit", "comparison": "<="|">=", "target", "actual", "passed": bool|null}], "passed", "failed", "not_measured"}`
(only once the run completed; `null` for capacity runs, which are judged per step).

`Capacity = {"steps": [Step], "criteria", "max_sustainable_users", "breaking_point_users",
"breaking_reason", "tested_up_to_users", "planned_steps", "stopped_early", "headline"}`,
`Step = {step, planned_users, users, seconds, complete, requests, rps, p95_ms, avg_ms,
failure_rate_pct, server_error_rate_pct, unexpected_outcome_rate_pct, rate_limited_pct,
verdict, failed_checks}`.

`SeriesSummary = {"repeats", "overall": {"throughput"|"p95"|"problem_rate": {"mean", "std", "min", "max", "values"}}, "cases": [{"case", "mean_rate", "min_rate", "max_rate", "runs"}]}`.

### `POST /api/cancel/{id}`
- `202 {"id", "status": "cancelling"}` (job, group or series; the status turns
  `cancelled` with partial results once the engine has stopped)
- `404 {"error": "run '<id>' not found"}`

---

## History, comparison, baselines

### `GET /api/jobs`
`200 {"jobs": [Row], "job_groups": [Row]}` — the 50 newest of each, newest first.
Jobs that belong to a group are not listed. `Row = {job_id | group_id, target_url,
users, status, created_at, completed_at, is_baseline, slo, mode, slo_verdict: "PASS"|"FAIL"|null}`.

### `POST /api/compare-jobs`
- `{"job_ids": [2–4 ids]}` → `200 {"jobs": [RunStatus], "case_comparison": {"min_requests": 5, "threshold": 10.0, "rows": [{"case", "cells": [{requests, failures, rate, low_sample}|null], "spread", "changed"}]}}`
- legacy `{"job_id_a", "job_id_b"}` → `200 {"job_a": RunStatus, "job_b": RunStatus}`
- `400 {"error": "job_ids must be a list of 2 to 4 run ids"}` · `400 {"error": "job_id_a and job_id_b are required"}`
- `404 {"error": "run '<id>' not found"}` · `404 {"error": "job_id_a '<id>' not found"}`

### `POST /api/baseline`
Request `{"id": runId}` →
`200 {"baseline_id", "target_url"}` · `400 {"error": "id (string) is required"}` ·
`400 {"error": "only a completed run can be the baseline"}` · `404 {"error": "run '<id>' not found"}`.
One baseline per target URL (setting one clears the previous).

### `GET /api/baseline?target_url=...`
`200 {"target_url", "baseline_id": id|null}` · `400 {"error": "target_url query parameter is required"}`

### `GET /api/regressions/{id}[?baseline_id=...]`
`200 {"target_url", "baseline_id", "is_current_baseline", "baseline": {"id", "users", "created_at", "is_group"}|null,
"regressions": [{"case", "baseline_rate", "current_rate", "increase", "baseline_requests", "current_requests"}],
"baseline_p95", "current_p95", "p95_change_pct", "threshold": 10.0, "min_requests": 5}`
(the comparison fields only when a different baseline run exists) ·
`404 {"error": "run '<id>' not found"}` · `404 {"error": "baseline run '<id>' not found"}`.

---

## Java additions (not used by the frontend)

### `GET /api/report/{id}`
The Markdown report of a run (`text/markdown`), used by the CLI and CI. `404` as above.

---

## CSV formats (as Locust writes them)

`stats_csv` — header
`Type,Name,Request Count,Failure Count,Median Response Time,Average Response Time,Min Response Time,Max Response Time,Average Content Size,Requests/s,Failures/s,50%,66%,75%,80%,90%,95%,98%,99%,99.9%,99.99%,100%`;
one row per case (Type = HTTP method, Name = case label or page path), then one
`Aggregated` row (Type empty). Times in milliseconds.

`failures_csv` — `Method,Name,Error,Occurrences`; Error is quoted. Runs with
expectations use messages such as `accepted_invalid_input (200): expected a 4xx rejection, got 200`.

`history_csv` — one `Aggregated` row per second:
`Timestamp,User Count,Type,Name,Requests/s,Failures/s,50%,95%,99%,Total Request Count,Total Failure Count,Total Average Response Time`
(Timestamp in Unix seconds; Requests/s and Failures/s are that second's counts;
percentiles over the last 5 seconds; Total* columns cumulative).

Percentiles come from HdrHistogram (microsecond values, 3 significant digits, so
each value is within 0.1%), and the histograms of parallel engine instances are
**added**, not averaged: a split run's merged p95 is the true p95 of all its requests.
