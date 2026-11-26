# Parity audit: load-tester (Python, `0c1bce0`) → load-tester-java

Written before the parity work on branch `ui-revamp-parity`, then updated as rows were
resolved. **Status** is the state found at audit time; **Resolution** says what was done.

- *same* — behaves identically (same fields, messages, status codes or files).
- *adapted* — same behaviour, different technology (Java instead of Python/Locust); the
  difference is deliberate and listed.
- *missing* — not in the Java repo at audit time; fixed unless the row says otherwise.

The backend of the Python repo did not change after the port
(`git diff 1223854 0c1bce0 -- code/*.py code/analysis code/locust_tests code/scripts code/tests`
is empty). The two later commits are frontend, design and documentation only.

## 1. REST API (`code/app.py` → controllers)

Checked by driving **both** backends through the same 29-call scenario (analyze, edge cases,
custom case, confirm, single, busy 429, split 60 users, series, capacity, cancel, website,
history, baseline, regressions, compare both forms, error bodies) and diffing every JSON
key path and status code of every response.

| Route | Java | Status | Resolution |
|---|---|---|---|
| `GET /api/health`, `GET /health` | `HealthController` | same | — |
| `POST /api/sanity-check` | `TargetController` | adapted | Probe response headers came back lower-case (`java.net.http`); now shown in conventional case (`Content-Type`). Error result omits `status_code`/`final_url` when they are `null` (Python sends `null`); the UI checks `!= null`, so both look the same. |
| `POST /api/analyze` | `TargetController` | adapted | As above. |
| `POST /api/generate-edge-cases` | `EdgeCaseController` | same | 27 cases, same labels, order, descriptions, expectations. |
| `POST /api/validate-custom-case` | `EdgeCaseController` | same | — |
| `POST /api/confirm-selection` | `EdgeCaseController` | same | — |
| `POST /api/start-load-test` | `LoadTestController` | same | Status codes 202/400/429 identical; queue-mode 409s added with queue mode (§5). |
| `POST /api/start-website-load-test` | `LoadTestController` | same | — |
| `GET /api/load-test-status/{id}` (job) | `JobView` | adapted | No `stdout`/`stderr` (Locust subprocess output; there is no subprocess) and no raw `capacity_steps` text (Locust step-shape output; the computed `capacity` object carries it). `header_names` was `[]` when empty, Python sends `null` → **fixed** (now `null`). Extra `job_type` (Python only had it on groups). |
| `GET /api/load-test-status/{id}` (group) | `GroupView` | missing fields | `child_jobs[]` rows lacked `target_url`, `spawn_rate`, `duration_seconds`, `group_id`, `header_names`, `is_baseline`, `mode`, `slo`, `case_expectations`, `capacity_plan`; group lacked `capacity_plan` → **fixed**. Extra `p95_exact: true` (Java adaptation, see §7). |
| `GET /api/load-test-status/{id}` (series) | `SeriesView` | same | Extra `p95_exact`. |
| `POST /api/cancel/{id}` | `LoadTestController` | same | 409 in queue mode added (§5). |
| `GET /api/jobs` | `HistoryController` | same | — |
| `POST /api/compare-jobs` (both forms) | `HistoryController` | same | Same child-row fix as above. |
| `POST /api/baseline`, `GET /api/baseline` | `BaselineController` | same | — |
| `GET /api/regressions/{id}` | `BaselineController` | adapted | Extra `p95_exact: true` so the new `BaselineComparison` can drop "≈" for a split baseline. |
| — | `GET /api/report/{id}` | Java addition | Markdown report for the CLI/CI; unused by the UI. |

Fields the **new** UI reads (`grep` of `hooks/`, `views/`, `components/`, `utils/`): every one
is returned by the Java backend. The only fields that differ (`stdout`, `stderr`,
`capacity_steps`, `null` vs absent `status_code`) are not read by the UI.

## 2. Analysis modules (`code/analysis/`)

| Python module | Java | Status |
|---|---|---|
| `capacity.py` | `analysis/capacity/CapacityPlanValidator`, `CapacityEvaluator`, `StepRecord`, `CapacityStep`, `CapacityResult`; step snapshots in `engine/StepTracker` | adapted (per-step p95 from a step histogram instead of Locust's rolling window) |
| `case_comparison.py` | `analysis/compare/CaseComparison` | same |
| `custom_cases.py` | `analysis/edgecase/CustomCaseClassifier`, `CustomCaseResult` | same (value previews are JSON instead of Python `repr`) |
| `edge_cases.py` | `analysis/edgecase/*Generator` (Strategy) + `EdgeCaseSuite` | same |
| `failure_summary.py` | `analysis/outcome/FailureSummarizer`, `FailureGroup`, `ResponseClassifier.meaningOf` | adapted (groups from structured outcomes, not by parsing Locust error strings) |
| `outcome_summary.py` | `analysis/outcome/OutcomeAnalysis.summarize`, `OutcomeSummary` | same |
| `problem_rate.py` | `OutcomeAnalysis.problemRate` | same |
| `regressions.py` | `analysis/compare/RegressionReport` | same |
| `request_headers.py` | `analysis/headers/RequestHeaders` | same (+ refuses headers `java.net.http` cannot set) |
| `sanity_check.py` | `analysis/probe/ReachabilityProbe`, `GuardedHttpClient`, `SanityResult` | same |
| `series_stats.py` | `analysis/compare/SeriesSummary` | same |
| `slo.py` | `analysis/slo/*` | same |
| `target_guard.py` | `analysis/target/TargetGuard`, `AddressClassifier`, `Cidr` | same |
| `type_detector.py` | `analysis/probe/TypeDetector`, `SitemapReader` | same (+ XXE-safe XML parser) |

## 3. Runner, storage, Locust files

| Python | Java | Status |
|---|---|---|
| `load_test_runner.py` | `service/LoadTestService`, `RunExecutor`, `RunRegistry`, `ActiveRunLimiter`, `SplitPlan`, `EngineSpecFactory` | adapted (engine in-process on virtual threads, no subprocess) |
| `stats_aggregator.py` | `analysis/stats/ResultMerger`, `LocustCsv` | adapted (histograms merged exactly instead of averaged percentiles) |
| `job_store.py` | `model/*` entities, `repository/*`, `service/RunStore` | adapted (JPA/H2 instead of sqlite3) |
| `locust_tests/dynamic_locustfile.py`, `website_locustfile.py` | `engine/LoadEngine`, `VirtualUser`, `RequestTemplate` | adapted (cases cycled from a random start instead of random choice) |
| `locust_tests/step_shape.py` | `engine/LoadProfile.Steps`, `StepTracker` | adapted |
| `locust_tests/expectations.py` | `analysis/outcome/ResponseClassifier` | same |

## 4. CLI and configs

| Python | Java | Status |
|---|---|---|
| `cli.py run` — flags `--config --api --url --sample --cases --mode --users --spawn-rate --duration --start-users --step-users --step-duration --max-users --slo-p95-ms --slo-avg-ms --slo-server-error-pct --slo-unexpected-pct --slo-min-rps --out-dir --i-own-target --header --timeout` | `cli/RunCommand` (picocli), `java -jar load-tester.jar run` | same flags, exit codes 0/1/2, outputs `report-<id>.md`, `result-<id>.json`, `summary.md` |
| `loadtest.example.yml`, `loadtest.ci.yml` | repo root | same (API base 8080) |

## 5. Scripts, queue mode, infrastructure

| Python | Java | Status | Resolution |
|---|---|---|---|
| `scripts/mock_target.py` | `mock-target` module | same | — |
| `scripts/regression_gate.py` + `gate_sample.json` | — | missing | **Added** `java -jar load-tester.jar gate ...` (`cli/GateCommand`) + `scripts/gate_sample.json`. |
| `scripts/check_sitemap_paths.py`, `check_extract_paths.py` | — | missing | **Added** as `java -jar load-tester.jar check-sitemap` (`cli/SitemapCheckCommand`). |
| `sqs_client.py`, `worker.py`, `Dockerfile.worker` | — | missing | **Added** queue mode: `queue/SqsJobQueue` (AWS SDK v2), `queue/QueueWorker`, `java -jar load-tester.jar worker`, `backend/Dockerfile.worker`; same env vars (`USE_SQS`, `SQS_QUEUE_URL`, `AWS_DEFAULT_REGION`), off by default, "not deployed" as in Python. |
| `code/k8s/*` | — | missing | **Added** `k8s/` (deployment, service, pvc, worker-deployment) with the Java images. |
| `code/.gitlab-ci.yml` | — | missing | **Added** `.gitlab-ci.yml` (Maven test, manual regression gate, build, deploy). |
| `code/RUNBOOK.md` | — | missing | **Added** `RUNBOOK.md`. |
| `Makefile` (MkDocs site only) | — | adapted | The Python Makefile only builds the course MkDocs site, which is not ported. **Added** a `Makefile` with the Maven/npm/docker equivalents of the day-to-day commands. |
| `.github/workflows/ci.yml` | `.github/workflows/ci.yml` | same jobs | backend tests, frontend lint/build, performance gate, image push |
| `.github/workflows/mkdocs.yml` | — | not ported | Course documentation site (see brief). |
| `code/Dockerfile`, `code/frontend/Dockerfile`, `docker-compose.yml` | `backend/Dockerfile`, `mock-target/Dockerfile`, `frontend/Dockerfile`, `docker-compose.yml` | adapted | — |

## 6. Tests (`code/tests/` → Java)

| Python test | Java test(s) |
|---|---|
| `test_app.py` | `controller/TargetControllerTest` (health, errors) |
| `test_baseline_endpoints.py` | `controller/HistoryAndBaselineControllerTest`, `it/ApiAgainstMockIT.historyCompareBaselineRegressionsAndReport` |
| `test_cancel.py` | `service/RunControlTest.cancelDependsOnWhatTheIdNames`, `engine/LoadEngineTest.stop*`, `it/ApiAgainstMockIT.cancelKeepsPartialResults` |
| `test_capacity.py` | `analysis/capacity/CapacityTest`, `engine/LoadEngineTest.stepProfileRecordsStepsAndStopsEarly`, `it/ApiAgainstMockIT.capacityRunStopsAtTheFirstFailingStep` |
| `test_case_comparison.py` | `analysis/compare/CompareTest` |
| `test_cli.py` | `cli/CliTest`, `it/ApiAgainstMockIT.cliPassesTheCiGateAndFailsOnTheBugs` |
| `test_compare_endpoint.py` | `controller/HistoryAndBaselineControllerTest.compareBothForms` |
| `test_custom_case_endpoints.py` | `controller/EdgeCaseControllerTest`, `service/EdgeCaseServiceTest` |
| `test_custom_cases.py` | `analysis/edgecase/CustomCaseClassifierTest` |
| `test_expectations.py` | `analysis/outcome/OutcomeClassificationTest` |
| `test_failure_summary.py` | `OutcomeClassificationTest.failuresAreGroupedByKindAndStatusLargestFirst` |
| `test_history_merge.py` | `analysis/stats/StatsTest.mergeAddsFailuresAndAlignsTimelineSeconds` |
| `test_problem_rate.py` | `OutcomeClassificationTest.problemRateCountsOnly5xx429AndConnection` |
| `test_regression_gate.py` | `cli/GateCommandTest` (added) |
| `test_regressions.py` | `CompareTest.regressionsAreCasesThatGotWorseByTenPoints` |
| `test_request_headers.py` | `analysis/headers/RequestHeadersTest` |
| `test_robustness.py` | `TargetGuardTest`, `ProbeTest`, `LoadTestServiceTest`, `RunControlTest`, `it/ApiAgainstMockIT.leftoverRunsAreMarkedInterruptedOnStartup` |
| `test_series.py` | `LoadTestServiceTest.repeatStartsASeries`, `it/ApiAgainstMockIT.seriesRunsBackToBack` (added) |
| `test_series_stats.py` | `CompareTest.seriesSummaryGivesMeanStdMinMax` |
| `test_slo.py` | `analysis/slo/SloTest` |
| `test_stats_aggregator.py` | `StatsTest` |
| `test_target_guard.py` | `analysis/target/TargetGuardTest` |
| — (sqs_client/worker untested in Python) | `queue/*Test` (added, fake queue) |

## 7. Frontend

| Item | Status | Resolution |
|---|---|---|
| `code/frontend/src` at `0c1bce0` (api.js, hooks/, views/, components/newtest, components/results, Icon, Popover, utils) | missing | **Ported** file for file. |
| `package.json`, `package-lock.json`, `index.html`, `vite.config.js`, `eslint.config.js`, `public/`, `README.md`, `Dockerfile`, `.gitignore`, `.dockerignore` | same | byte-identical |
| `nginx.conf`, `.env.example` (+ `.env.development`, `.env.production`) | adapted | backend on 8080 |
| Default API base (`api.js`) | adapted | 8080 |
| "≈ / estimate" for split runs | adapted | shown only when `child_jobs` is present **and** `p95_exact` is not; Java always sends `p95_exact: true`, the Python backend never does, so the same UI shows ≈ against Python. |
| "Locust process(es)" wording | adapted | "engine instance(s)" (capacity texts, split-run chip when `p95_exact`, report). |
| `timeout` message | adapted | wording no longer names Locust; branch kept (Java never produces `timeout`). |

## 8. Product, design, agent files and docs

| Python | Java | Status | Resolution |
|---|---|---|---|
| `PRODUCT.md`, `DESIGN.md`, `.impeccable/*`, `skills-lock.json` | — | missing | **Copied**; PRODUCT.md adapted (Java engine, course line placeholder). |
| `.claude/skills/**`, `.claude/agents/**` | — | missing | **Copied** verbatim. |
| `.claude/launch.json` | — | missing | **Rewritten** for this repo (backend, mock, frontend). |
| `docs/features.md`, `docs/evaluation.md`, `docs/ux-review.md`, `docs/ux-revamp-plan.md`, `docs/OVERNIGHT_SUMMARY.md` | — | missing | **Ported** with Java implementation details; every number measured on the Java build. |
| `.gitignore` additions of `8464513` | — | missing | **Merged**. |
| team journals, `project-report-prototype-stage/`, `docs/criteria-for-project-selection.md`, MkDocs site | — | not ported | Software Engineering course material (per brief). |

## 9. Verification (run on this machine, November 2026)

- **Same scenario, both backends:** 29 API calls (analyze, cases, confirm, fixed, split,
  series, capacity, cancel, website, history, compare, baseline, regressions, report, error
  cases) sent to the Python backend (:5000, Python mock) and the Java backend (:8080, Java mock);
  every JSON key path diffed. The only differences are the ones listed in sections 1–7 (such as
  `p95_exact`); values that depend on timing were not compared.
- **Side by side in the browser:** the ported frontend against the Java backend and against the
  Python backend (and the Python repo's own frontend) shows the same views, order and controls.
  Against Java a split run reads "Split across 2 engine instances" with an exact p95; against Python
  the same build reads "Split across 2 processes" and "≈… estimate".
- **One behavioural difference found:** `java.net.http` connects only to the first address a host
  name resolves to. Analyzing `http://localhost:5173` (Vite bound to `::1` only) fails from Java and
  works from Python's `requests`. Documented in the README limitations; `127.0.0.1` or `[::1]` work.
- **Bug found and fixed while comparing:** a case's p95 could read slightly above its max
  (HdrHistogram bucket tops). Percentiles are now clamped to the exact min/max.
