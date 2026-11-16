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
- `.env.development`, `.env.production` added (VITE_API_BASE=http://localhost:8080); `.env.example` now says 8080.
- `nginx.conf`: proxy to `backend:8080` instead of `backend:5000`.
- "≈ / approximate p95" is now shown only when a split run is NOT marked `p95_exact` (the Java backend always sends
  `p95_exact: true`), with an "exact (merged histograms)" note instead: `JobResultSummary.jsx`, `CompareView.jsx`,
  `SeriesSummary.jsx`, `utils/report.js`, `App.jsx` (badge tooltip). Against the old Python backend it still shows ≈.
- Wording "Locust process(es)" → "engine instance(s)": `App.jsx` badge, `CapacityConfig.jsx`, `CapacityResult.jsx`,
  `report.js`; "Locust reports the average" → "The backend reports the average" (`Timeline.jsx`); one comment in `SloVerdict.jsx`.
- No change to any fetch call, field name or component structure.

## Bugs found by testing (and fixed)
- Users started at case i: short runs never reached the last cases (both 500 bugs missed). Now a random start case.
- Capacity steps were closed one sample late (early stop ~1 s into the next step; a failing next-to-last step
  could not stop the run; a 1 s "partial" step appeared). Steps now close at their boundary.
- CLI printed "null" for a refused connection; now names the exception.

## Skipped / not ported (deliberately)
- SQS queue mode and Kubernetes manifests of the Python version (single-process design; see README limitations).
- "timeout" status: the Java engine stops itself at the end of the duration, so it never needs a watchdog.
