# Elevate Load Tester (Java Edition)

A load tester for HTTP APIs and websites that does more than measure speed: it
generates edge cases from one sample request (missing fields, nulls, wrong types,
boundary values, SQL injection, XSS), knows what a correct API *should* answer to
each, and reports which answers were wrong while the target is under load. Runs are
judged against an SLO (PASS/FAIL), compared with each other and with a baseline,
and a capacity finder climbs the load step by step to find the breaking point.

This is the Java 21 rewrite of our earlier Python version (Flask + Locust). The
backend is a genuine Java implementation: **our own load engine** on virtual threads
with `java.net.http.HttpClient` and HdrHistogram, no Locust, Gatling or JMeter. The
React frontend is the same app, talking to the Java backend through the same REST API.

**Team:** Anant Agrawal, Vivek Pandey, Piyush

## Features

| Feature | Where |
|---|---|
| Target analysis: reachability with read-only probes (HEAD → GET → OPTIONS), API vs website detection, sitemap discovery | `analysis.probe` |
| SSRF guard: private, loopback, link-local and multicast targets blocked unless `ALLOW_PRIVATE_TARGETS=true`; cloud metadata always blocked; every redirect hop checked | `analysis.target` |
| 27 edge cases from a 3-field sample, one Strategy class per kind, each with an expected answer (2xx or 4xx) | `analysis.edgecase` |
| Custom cases, validated by diffing them against the sample | `CustomCaseClassifier` |
| Load engine: virtual users on virtual threads, ramp-up, hold, clean cancel | `engine` |
| Exact latency percentiles with HdrHistogram, per case and overall; per-second timeline | `CaseRecorder`, `IntervalSampler` |
| Outcome classification: handled correctly, accepted invalid input, rejected valid input, server error, rate limited, connection error | `ResponseClassifier` |
| Runs above 50 users split into parallel engine instances, histograms merged **exactly** | `SplitPlan`, `ResultMerger` |
| SLO thresholds and PASS/FAIL verdicts | `analysis.slo` |
| Capacity finder: step load, per-step SLO, max sustainable users and breaking point, early stop | `analysis.capacity`, `StepTracker` |
| Run history, compare 2–4 runs, baselines and regression flags, repeat series with statistics | `RunViewService`, `BaselineService`, `analysis.compare` |
| Markdown report (browser download and `GET /api/report/{id}`) | `ReportService` |
| CLI for CI pipelines: exit 0 (pass), 1 (fail), 2 (error) | `cli` |
| Java mock target with known deliberate bugs | `mock-target` module |

The API contract (every endpoint, field and status code) is in
[docs/api-contract.md](docs/api-contract.md).

## Quick start

### Docker (one command)

```bash
docker compose up --build
```

Open http://localhost:8080. The app opens pre-filled for the mock target
(`http://mock:8000/api/login`, sample `{"username": "demo", "password": "demo123", "expiresInMins": 30}`).

### From source

Needs Java 21 and Node 24 (Maven comes with the wrapper).

```bash
./mvnw verify
```

```bash
java -jar mock-target/target/mock-target-1.0.0-exec.jar
```

```bash
java -jar backend/target/load-tester.jar
```

```bash
cd frontend && npm ci && npm run dev
```

Then open http://localhost:5173 and analyze `http://127.0.0.1:8000/api/login`.
The backend listens on http://localhost:8080 and keeps its H2 database in `./data`.

### Settings (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | backend port |
| `ALLOW_PRIVATE_TARGETS` | `true` | allow private/loopback targets (set `false` on a public server) |
| `MAX_ACTIVE_RUNS` | `1` | load tests in progress at once; more gives 429 |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | browser origins allowed to call the API |
| `DATA_DIR` | `./data` | where the H2 database lives |
| `RECOVER_STALE_RUNS` | `true` | mark runs left running by a previous process as interrupted |
| `MOCK_PORT`, `MOCK_RATE_LIMIT`, `MOCK_WORKERS`, `MOCK_REQUIRE_TOKEN` | `8000`, `40`, `8`, none | mock target |

## CLI

The jar has a command-line mode that runs one test through the REST API and exits
with its verdict:

```bash
java -jar backend/target/load-tester.jar run --config loadtest.example.yml
```

```bash
java -jar backend/target/load-tester.jar run --config loadtest.ci.yml --users 20 --duration 15
```

Exit codes: `0` SLO passed (capacity mode: at least `min_users` sustained), `1` SLO
failed, `2` error. It writes `report-<id>.md`, `result-<id>.json` and `summary.md`
(the verdict table, which CI appends to the job summary) into the output directory.
`loadtest.ci.yml` is the CI performance gate: the mock's three known bugs are
excluded, so it passes on a healthy build and fails on new crashes or slowdowns.

## Architecture

```
frontend (React)  ──REST/JSON──▶  controller ─▶ service ─▶ engine (virtual threads, HdrHistogram)
                                                  │            │
                                                  ▼            ▼
                                       repository (JPA/H2)   target (HTTP)
                                                  │
                                              analysis (pure Java: guard, probe, edge cases,
                                                        outcomes, SLO, capacity, compare)
```

- **controller** — thin REST layer, one class per area; DTOs are Java records.
- **service** — validation, run lifecycle, background execution, read models.
- **engine** — `LoadEngine` runs one load profile; `VirtualUser` per user; `CaseRecorder`
  (HdrHistogram `Recorder`) per case; `IntervalSampler` once a second.
- **analysis** — plain Java, no Spring, fully unit-tested.
- **model / repository** — JPA entities (`TestRun`, `SplitGroup`, `RepeatSeries`,
  `RunCase`, `CaseResult`) and Spring Data repositories.

Results stay in Locust's CSV formats on the wire (the frontend parses them), but the
backend stores structured data — counts, compressed HdrHistograms, failure tallies,
per-second samples — and renders the CSVs when a run is read.

Diagrams: [docs/architecture.md](docs/architecture.md). Java concepts and viva
questions: [docs/java-concepts.md](docs/java-concepts.md). Demo:
[docs/demo-script.md](docs/demo-script.md). Build log and decisions:
[PROGRESS.md](PROGRESS.md).

## Testing

`./mvnw verify` runs 236 tests: 24 for the mock target, 203 backend unit and MockMvc
tests, and 9 integration tests that start the Java mock on a random port and run real
load tests of a few seconds (engine alone, and the whole app over HTTP including the
CLI). JaCoCo enforces at least 80% line coverage on `service`, `engine` and `analysis`
(currently 91%; report in `backend/target/site/jacoco/index.html`).

CI (`.github/workflows/ci.yml`): Maven build and tests, frontend lint and build, the
performance gate (mock + backend + CLI with `loadtest.ci.yml`), and on `main` the
images pushed to GHCR.

## Known limitations

- Runs execute inside the backend process. Split runs are parallel engine instances on
  the same machine, not a distributed cluster (the Python version's SQS queue mode and
  Kubernetes manifests were not ported).
- Percentiles are exact to HdrHistogram's resolution: 3 significant digits (±0.1%).
  Merging is exact; a recorded 100 ms may read back as 100.03 ms.
- Load-test requests never follow redirects (only the first URL passes the target
  guard). A website that redirects every page measures the redirect.
- If the backend stops, running tests end as "interrupted"; request header values are
  never stored, so they cannot be resumed.
- Website runs send GET requests to at most 20 pages from the sitemap.
- One H2 database per backend: there is no shared state between several backends.
