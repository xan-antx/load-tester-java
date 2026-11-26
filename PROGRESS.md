# Progress — Elevate Load Tester (Java Edition)

Checklist for the build, kept up to date so work can resume after an interruption.
`[x]` done, `[ ]` open, `[-]` skipped (with the reason).

## Phase 0 — Project setup and API contract
- [x] Read the Python reference (app.py, analysis/, load_test_runner.py, locust_tests/, mock_target.py, cli.py, frontend, docs)
- [x] Checked versions on Maven Central: Spring Boot **4.1.2** (latest stable; 4.2.0 is RC), HdrHistogram 2.2.2, picocli 4.7.7, JaCoCo 0.8.16, Maven 3.9.16 via wrapper 3.3.4
- [x] Maven multi-module build: parent `com.elevate:load-tester-parent`, modules `mock-target` and `backend` (`com.elevate:load-tester`)
- [x] Maven wrapper (`./mvnw`, `mvnw.cmd`), checksum of the wrapper zip verified
- [x] `docs/api-contract.md` — every endpoint, request/response JSON and status codes
- [x] Frontend copied to `./frontend` (no node_modules/dist/.env); `.env.development` and `.env.production` set `VITE_API_BASE=http://localhost:8080`; `npm run build` and `npm run lint` pass

## Phase 1 — Core domain, persistence and target analysis
- [x] JPA entities, repositories, H2 file database in `data/`
- [x] Reachability probe (HEAD → GET → OPTIONS, manual redirects) and type detection
- [x] Target guard (private/loopback/link-local/multicast, metadata always), `i_own_target`
- [x] Edge-case strategies (Strategy pattern) and custom-case validation
- [x] Global exception handling, Bean Validation
- [x] CORS via `CORS_ALLOWED_ORIGINS`

## Phase 2 — Load engine
- [x] Virtual users on virtual threads, java.net.http.HttpClient, think time
- [x] Ramp-up, hold, clean stop and cancel
- [x] HdrHistogram recording (Recorder per case, merged exactly)
- [x] Outcome classification
- [x] Per-second timeline sampler
- [x] Split runs (> 50 users) as parallel engine instances, exact merge
- [x] Async runs, statuses, MAX_ACTIVE_RUNS (429), interrupted on startup

## Phase 3 — Results
- [x] SLO validation and verdicts
- [x] History, compare, baselines/regressions, Markdown report
- [x] Frontend works end to end against the Java backend (driven in a browser: analyze, generate, expectation toggles,
      custom case, fixed run + SLO, baseline + regressions, split run, compare, capacity, stop, report download)

## Phase 4 — Capacity, mock, CLI
- [x] Capacity mode
- [x] Java mock target (com.sun.net.httpserver)
- [x] CLI (picocli) code — `java -jar load-tester.jar run --config ...`
- [x] loadtest.example.yml / loadtest.ci.yml; CLI run end to end: CI config exit 0, example config exit 1, unreachable backend exit 2

## Phase 5 — Testing
- [x] Unit tests for every analysis rule, edge-case strategy, outcome classification, SLO, capacity, histogram merge, target guard
- [x] @WebMvcTest/MockMvc tests for every controller (status codes, JSON field names, error bodies, CORS)
- [x] Integration tests (Failsafe, *IT): engine vs Java mock; whole app over HTTP incl. split/cancel/capacity/429/CLI/recovery
- [x] JaCoCo gate 80% on service+engine+analysis: actual 91.2% (analysis 96.9%, engine 94.6%, service 81.4%; all code 90.5%)
- Totals: 236 tests = 24 mock-target + 203 backend unit/MockMvc + 9 integration

## Phase 6 — CI/CD, Docker, docs
- [x] .github/workflows/ci.yml: Maven verify + coverage artifact, frontend lint/build, performance gate (mock + backend + CLI,
      summary to $GITHUB_STEP_SUMMARY, report artifact), GHCR push on main
- [x] backend/Dockerfile, mock-target/Dockerfile (multi-stage, JRE 21, non-root), frontend/Dockerfile (existing, nginx), docker-compose.yml
- [x] README.md, docs/architecture.md (3 Mermaid diagrams), docs/java-concepts.md (+25 viva Q&A), docs/demo-script.md

## Phase 7 — UI revamp parity (branch `ui-revamp-parity`, from load-tester `0c1bce0`)
- [x] Backend of the Python repo unchanged since the port (`git diff 1223854 0c1bce0 -- code/*.py code/analysis code/locust_tests code/scripts code/tests` is empty)
- [x] `docs/parity-audit.md`: every route, module, script, test, CI/infra file and UI field mapped; both backends driven through the same 29-call scenario and every JSON key path diffed
- [x] Revamped frontend (`code/frontend/src` at 0c1bce0) ported file for file; rest of frontend/ byte-identical except nginx.conf/.env*
- [x] Java adaptations re-applied to the new files (see "Frontend changes")
- [x] Backend fields aligned with the Python contract: `child_jobs` rows complete, group `capacity_plan`, `header_names: null` when empty, probe header names in conventional case, `p95_exact` on /api/regressions
- [x] PRODUCT.md (adapted), DESIGN.md, .impeccable/, skills-lock.json, .claude/skills, .claude/agents (verbatim), .claude/launch.json (rewritten), .gitignore additions merged
- [x] Queue mode: `queue/` (JobQueue, SqsJobQueue on AWS SDK v2 2.59.10, JobMessage, QueueWorker, WorkerMode, QueueConfig), `java -jar load-tester.jar worker`, backend/Dockerfile.worker, 409 refusals, H2_AUTO_SERVER
- [x] k8s/ manifests, .gitlab-ci.yml, Makefile, scripts/gate_sample.json, `gate` and `check-sitemap` CLI subcommands
- [x] RUNBOOK.md (Java commands; worker, gate, check-sitemap, k8s, troubleshooting)
- [x] docs ported: features, evaluation, ux-review, ux-revamp-plan, OVERNIGHT_SUMMARY — every figure measured on the Java build (runs listed below); figures from the Python-only review sessions (critique score, pixel distances, session row counts) reworded without numbers
- [x] ./mvnw verify green: 254 tests (24 mock, 219 backend unit/MockMvc, 11 integration), 0 failures; JaCoCo 93.2% on service+engine+analysis (gate 80%), 91.8% all code
- [x] Browser walkthrough of the new UI against the Java backend (analyze, cases, expectations, custom case, headers, fixed run, baseline, report, split, series, capacity, stop, website run, History compare, make baseline); side by side with the Python version: same layout and flows, only the documented differences (p95 exact vs ≈, "engine instances" vs "processes")
- [x] CLI exit codes: `loadtest.ci.yml` 0, `loadtest.example.yml` 1, backend down 2, `gate` 0 (15 users) / 1 (150 users, 15 cases worse), capacity 0 (120 sustained); README screenshots in docs/images/
- [x] README, architecture (queue/k8s diagrams), api-contract, demo-script, java-concepts updated
- [x] Commits, push and PR: committed as 3026d43 on `ui-revamp-parity`, PR xan-antx/load-tester-java#1 opened to `main`
- [x] PR #1 merged into `main` on 26 Nov 2026; branch `ui-revamp-parity` deleted
- [-] PRODUCT.md course name/code: still `<FILL IN: Java lab course name/code>` (lines 16 and 123); the course value provided was itself an unfilled template, so nothing was invented

## Decisions
- **Results stay in Locust's CSV formats on the wire.** The React app parses `stats_csv`,
  `failures_csv` and `history_csv`; the Java backend stores structured results
  (counts, HdrHistograms, failure tallies, per-second samples) and renders those CSVs on read.
- **Duration includes ramp-up**, as in Locust (`-t 30s` is the whole run).
- **Cases are cycled** per virtual user, each user starting at a random case. (First version
  started user *i* at case *i*: with 15 users and 27 cases the last cases — including both
  500 bugs — were never sent in a 10 s run. Found in the smoke test, fixed.)
- **Multi-module Maven**: the mock target is its own module with no Spring; the backend uses
  it as a test dependency for integration tests.

## Smoke test against the Java mock (manual, 16 Nov)
- 15 users × 20 s, all 27 cases, optional-field cases set to accept: 228 requests, all 3 deliberate bugs found
  (500 ×16 on the two expiresInMins crash cases, 200 ×9 on very_long_string_username), verdict FAIL on server errors 7.02%.
- Split run 120 users → 3 engine instances, merged; 429s reported separately from bugs.
- Capacity 20→200 step 20, 12 s steps: max sustainable 120, breaking point 140 (p95 620 ms) — same as the Python evaluation.
- Cancel keeps partial results; series of 2 with series_summary; website run with sitemap (asset filtered, 404 grouped as rejected).
- Baseline, regressions, compare (3 runs), report, guard (metadata blocked), 429 busy, error bodies all checked with curl.

## Other decisions
- Load-test requests never follow redirects (only the first URL passes the target guard).
- The API URL's query string is kept (Python dropped it).
- Request headers that java.net.http refuses (Host, Connection, Content-Length, Expect, Upgrade) are rejected with a clear 400.
- `GET /api/report/{id}` (Markdown) added for the CLI and CI; not used by the frontend.
- Group results are merged on read (exactly), not cached.

## Frontend changes (all listed)
Phase 6 (original frontend):
- `.env.development`, `.env.production` added (VITE_API_BASE=http://localhost:8080); `.env.example` now says 8080.
- `nginx.conf`: proxy to `backend:8080` instead of `backend:5000`.

Phase 7 (revamped frontend, `code/frontend/src` at load-tester 0c1bce0, copied file for file, then):
- `api.js`: default API base `http://localhost:8080`.
- "≈ / approximate p95" only when a split run does NOT send `p95_exact` (the Java backend always sends
  `p95_exact: true`); otherwise the note "exact (merged histograms)": `utils/runReport.js` (`p95Approx`),
  `results/RunResults.jsx` (+ P95_EXACT_HELP), `results/CaseResultsTable.jsx`, `results/BaselineComparison.jsx`,
  `CompareView.jsx`, `SeriesSummary.jsx`, `utils/report.js` (footnote and "Engine instances" line),
  `views/RunView.jsx` (split chip and popover). Against the Python backend the same build still shows ≈.
- Wording "parallel processes"/"Locust" → "engine instance(s)": `CapacityConfig.jsx`, `CapacityResult.jsx`,
  `views/RunView.jsx`, `utils/report.js`; timeout text "The load test did not finish…" (`RunResults.jsx`).
  The `timeout` status branch is kept (the Python backend can still send it).
- Comments only: `SloVerdict.jsx`, `hooks/useRun.js`, `utils/estimate.js` (Java cycles cases from a random start).
- Line endings kept as in the Python repo. No change to any fetch call, field name or component structure.

`grep -i "Locust|5000|≈|approx" frontend/src` — remaining hits, each justified:
- "Locust's … CSV" comments (`Timeline.jsx`, `parseFailuresCsv.js`, `runReport.js`): the wire format is still Locust's CSV.
- `estimate.js` comment: describes the Python/Locust case choice next to the Java one.
- `HEALTH_POLL_MS = 15000` (`useHealth.js`): a poll interval, not the port.
- ≈ / approx branches in `CompareView`, `SeriesSummary`, `SloVerdict`, `Timeline`, `BaselineComparison`,
  `CaseResultsTable`, `RunResults`, `report.js`, `RunView`: all gated on `!p95_exact`, so they only show
  against the Python backend; kept so one frontend works with both.

## Phase 7 decisions
- **Exact p95 marked, not hidden:** the backend sends `p95_exact`; the UI drops ≈ only when it is set.
- **Python contract alignment:** `child_jobs` rows carry every Python field, groups carry `capacity_plan`,
  `header_names` is `null` when empty, probe header names title-cased; `p95_exact` also on /api/regressions.
- **Percentiles clamped to [min, max]:** HdrHistogram reads back a bucket's top value, so p95 could exceed the
  slowest request (152.06 > 152 ms). `CaseStats.of` clamps; test `percentilesNeverReadAboveTheSlowestRequest`.
- **Queue mode like Python:** off by default, same env vars (`USE_SQS`, `SQS_QUEUE_URL`, `AWS_REGION`), the
  worker is `java -jar load-tester.jar worker`; request headers and capacity runs are refused with 409 in queue
  mode (headers are never stored; Python has the same limits); the API keeps the MAX_ACTIVE_RUNS slot by polling
  the shared H2 (`H2_AUTO_SERVER=TRUE`). Tested with a fake queue; not deployed, as in Python.
- **Makefile instead of MkDocs:** the mkdocs site was not to be copied; `make` wraps mvnw/npm/CLI commands.
- **IPv6 localhost:** `java.net.http` tries only the first resolved address, so a site bound to ::1 only (Vite's
  `localhost`) is unreachable from Java while Python's requests falls back. Documented, not worked around.
- **Docs figures:** every number in the ported docs comes from a run on the Java build (Nov 2026, Java mock,
  this machine); numbers that existed only in the Python review sessions were replaced by words.

## Measured runs used in the docs (Java build, Java mock)
- 15 users 15 s, all cases, default expectations: 171 requests, failure rate 24.6%, problem rate 11.1%,
  p95 147.97 ms, server errors 11.11%, all 27 cases sent.
- 150 users 15 s: 1163 requests, 3 instances, p95 785.92 ms (exact), 429 ×562 across 27 cases, 500 ×57,
  32 distinct errors, 15 regressions against the 15-user baseline (gate: 15 cases worse, exit 1).
- 15 users 20 s, optional fields 2xx: 226 requests, p95 147.71 ms, server errors 7.52%, handled 84.51%.
- Smoke 5 users 20 s: 75 requests, all 27 cases sent (UI estimate warned about 2).
- Series 15 users 10 s ×3: 31 s, 11.03 ± 0.29 req/s, p95 148.78 ± 1.12 ms.
- Stop a 30 s run at 10 s: cancelled, 111 requests kept, all 27 cases.
- Capacity (CLI, valid_sample, defaults): 84 s, max sustainable 120, breaking point 140 (p95 691.71 ms), ~79 req/s plateau.
- CLI: ci config exit 0 (189 requests, p95 149.5 ms, 100% handled); example config 15 s exit 1 (server errors 6.55%).

## Bugs found by testing (and fixed)
- Users started at case i: short runs never reached the last cases (both 500 bugs missed). Now a random start case.
- Capacity steps were closed one sample late (early stop ~1 s into the next step; a failing next-to-last step
  could not stop the run; a 1 s "partial" step appeared). Steps now close at their boundary.
- CLI printed "null" for a refused connection; now names the exception.

## Skipped / not ported (deliberately)
- "timeout" status: the Java engine stops itself at the end of the duration, so it never needs a watchdog.
