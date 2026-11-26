# Evaluation: how we know it works

This page explains how we measure that the Java edition of Elevate Load Tester does its
job, and reports the numbers from real runs. Every figure comes from a run on the Java
build (Spring Boot backend, our own load engine, Java mock target) made on 26 November
2026 on one Windows laptop, unless a row says otherwise. None of them are estimates.

The runs used the bundled **mock target** (`mock-target/`, `java -jar
mock-target/target/mock-target-1.0.0-exec.jar`), a small login API whose behaviour we
control. Because we know exactly how it *should* behave, we can check whether the tool
reports the right things.

## 1. What a correct run looks like on the mock target

The mock is built with known properties:

- **Correct handling.** Valid credentials (`demo` / `demo123`) give 200.
  Missing, null, empty or wrong-type usernames and passwords, wrong
  credentials and a negative `expiresInMins` give 400.
- **Optional field.** `expiresInMins` is optional: missing, null or 0 is
  accepted.
- **Three deliberate bugs:**
  1. `expiresInMins` as text: unhandled exception, **500**;
  2. `expiresInMins` above 1,000,000: unhandled exception, **500**;
  3. a username longer than 256 characters: **accepted with 200**, a
     validation bug.
- **Load limits.** A global rate limit of 40 requests/s (beyond it, 429), and a fixed
  pool of 8 worker threads that each spend 50–150 ms per request (about 80 requests/s).
  Beyond that, requests queue and latency climbs.

A correct tool should therefore:

- find exactly the three bugs and report nothing else as wrong;
- report a 429 as rate limiting, not as a bug;
- put the capacity limit near the point where the mock runs out of workers.

## 2. Expected vs actual edge-case outcomes

**Run:** the CLI with `loadtest.example.yml` (all 27 generated cases, 15 users for 30 s,
`missing_expiresInMins` and `null_expiresInMins` set to "expects 2xx" because the field is
optional).

| Result | Value |
|---|---|
| Requests | 337 |
| Handled correctly | **303 (89.91%)** |
| Accepted invalid input | 13 requests, **1 case**: `very_long_string_username` |
| Server errors (500) | 21 requests, **2 cases**: `wrong_type_expiresInMins`, `very_large_expiresInMins` |
| Rejected valid input | 0 |
| Rate limited (429) | 0 (about 11 requests/s, under the 40/s limit) |
| p95 / average | 146 ms / 103 ms |

Per case:

| Case | Expects | Requests | Handled correctly | What went wrong |
|---|---|---|---|---|
| valid_sample | 2xx | 12 | 12 | — |
| missing_username | 4xx | 13 | 13 | — |
| null_username | 4xx | 13 | 13 | — |
| wrong_type_username | 4xx | 13 | 13 | — |
| empty_string_username | 4xx | 13 | 13 | — |
| **very_long_string_username** | 4xx | 13 | **0** | **accepted invalid input (200) ×13** — the deliberate validation bug |
| sqli_username_0 / 1 / 2 | 4xx | 13 / 12 / 14 | all | — |
| xss_username_0 / 1 | 4xx | 13 / 12 | all | — |
| missing_password | 4xx | 12 | 12 | — |
| null_password | 4xx | 12 | 12 | — |
| wrong_type_password | 4xx | 13 | 13 | — |
| empty_string_password | 4xx | 13 | 13 | — |
| very_long_string_password | 4xx | 13 | 13 | — |
| sqli_password_0 / 1 / 2 | 4xx | 14 / 14 / 15 | all | — |
| xss_password_0 / 1 | 4xx | 14 / 14 | all | — |
| missing_expiresInMins | 2xx | 11 | 11 | — |
| null_expiresInMins | 2xx | 10 | 10 | — |
| **wrong_type_expiresInMins** | 4xx | 11 | **0** | **server error (500) ×11** — deliberate bug |
| **very_large_expiresInMins** | 4xx | 10 | **0** | **server error (500) ×10** — deliberate bug |
| negative_expiresInMins | 4xx | 10 | 10 | — |
| zero_expiresInMins | 2xx | 10 | 10 | — |

**Verdict:** all 3 deliberate bugs were caught, and all 24 other cases were handled
correctly, so there were no false findings. Every case got between 10 and 15 requests:
the Java engine has each virtual user cycle through the cases from a random start, so
the requests are spread evenly.

**Split run.** At 120 users (3 parallel engine instances on the same host, 12 s, same
config), the merged result still showed only `very_long_string_username` as accepted
invalid input (25 requests):

- 39 server errors (500), from the two crash cases;
- 411 rate-limited (429), correctly kept separate;
- the run reports `p95_exact: true`: the three instances' HdrHistograms were added, so
  the merged p95 (450 ms) is the true p95 of all 907 requests, and the UI shows it without "≈".

The same browser session against the Python backend showed a 60-user split run's p95 as
"≈287 ms — estimate (split run)", while the Java run with the same settings showed
"152 ms — exact (merged histograms)".

## 3. SLO thresholds

Default SLO in the app: **p95 ≤ 500 ms** and **server errors (5xx + no answer)
≤ 1%**. Three more thresholds are optional: average response time,
unexpected-answer rate and minimum throughput.

| Run | p95 | Server errors | Unexpected answers | Verdict |
|---|---|---|---|---|
| Evaluation run above (SLO also had unexpected ≤ 5%) | 146 ms ✓ | 6.23% ✗ | 3.86% ✓ | **FAIL** |
| Browser run, 15 users × 15 s, all cases + one custom case | 151 ms ✓ | 6% ✗ | — | **FAIL** |
| CI gate config (known bugs excluded), local | 150 ms ✓ | 0% ✓ | 0% ✓ | **PASS** |

The failures are correct: the mock really does crash on two inputs. The SLO
turns that into a clear FAIL rather than a number the reader has to judge.

## 4. Capacity on the mock target

**Settings:** only `valid_sample` selected; start 20 users, add 20 per step,
12 s per step, up to 200; spawn rate 20/s; SLO p95 ≤ 500 ms and server
errors ≤ 1%; early stop on.

| Run | Max sustainable | Breaking point | Why it broke | Run time |
|---|---|---|---|---|
| Browser UI (local, 26 Nov) | **120 users** | **140 users** | p95 592 ms > 500 ms | 84 s (early stop) |
| API with curl (local, 16 Nov, earlier Java build) | 120 users | 140 users | p95 621 ms > 500 ms | early stop |

Steps of the browser run:

| Users | Requests/s | p95 ms | 429 share | Verdict |
|---|---|---|---|---|
| 20 | 14.8 | 147 | 0% | PASS |
| 40 | 30.1 | 149 | 0% | PASS |
| 60 | 44.1 | 148 | 9.5% | PASS |
| 80 | 59.0 | 170 | 32.1% | PASS |
| 100 | 72.5 | 222 | 44.9% | PASS |
| 120 | 78.5 | 410 | 48.8% | PASS |
| 140 | 79.3 | **592** | 49.5% | **FAIL** |

This matches the mock's design. Throughput levels off at about 80 requests/s,
the 8 workers' limit, and p95 climbs once requests queue for a worker. Both runs agree
on 120 / 140 users, as did the Python version on its own mock. The step size (20 users)
is the resolution: the true limit lies between 120 and 140 users. Each step's p95 comes
from a histogram of that step's requests only, and a step is closed and judged exactly at
its boundary, so early stop leaves no partial step behind.

## 5. The CI performance gate

`.github/workflows/ci.yml` runs on every push and pull request to `main`. It
starts the mock target and the backend, and runs
`java -jar backend/target/load-tester.jar run --config loadtest.ci.yml`: 10 users for
25 s, the three known bugs excluded, p95 ≤ 500 ms, server errors ≤ 1%, unexpected
answers ≤ 1%.

| Where | Requests | Handled correctly | p95 | Verdict | Exit code |
|---|---|---|---|---|---|
| Windows, local backend | 189 | 100% | 150 ms | PASS | 0 |
| Example config (all cases, bugs included), local | 337 | 89.91% | 146 ms | **FAIL** (server errors 6.23%) | **1** |
| Backend not running | — | — | — | error | **2** |

The second row shows the gate failing when it should. The workflow writes the
verdict table into the job summary and uploads the report and JSON as
artifacts. The regression gate (`java -jar backend/target/load-tester.jar gate`, 10
users for 10 s against the stored baseline) exited 0 with "PASS: no case's failure rate
rose by 10.0 points or more."

## 6. Automated tests

| Suite | Count | Where it ran |
|---|---|---|
| `./mvnw verify`: mock target unit tests | **24 passed** | Windows, Java 21 |
| `./mvnw verify`: backend unit and MockMvc tests | **219 passed** | Windows, Java 21 |
| `./mvnw verify`: integration tests (real load against the Java mock on a random port) | **11 passed** | Windows, Java 21 |
| JaCoCo line coverage, service + engine + analysis (build fails below 80%) | **93.2%** (all code 91.8%) | same run |
| Frontend lint (ESLint) | 0 problems | local |
| Frontend production build | passes | local |

The tests cover every analysis rule, every edge-case strategy, outcome classification,
SLO and capacity evaluation, exact histogram merging, the target guard, every
controller's status codes and error bodies, the CLI and the regression gate (mocked
backend), queue mode (a fake queue and a mocked SQS client; no AWS), and real load tests
against the mock: single, split, cancelled, capacity, series and website runs, the 429
busy answer, and the CLI exit codes.

## 7. Safety checks

| Check | Result |
|---|---|
| Analyze `http://169.254.169.254/latest` | 400 "Target not allowed: … a cloud metadata address", `blocked_target: true` |
| Start without `i_own_target: true` | 400 "Confirm that you own this target…", `missing_confirmation: "i_own_target"` |
| Redirect to a metadata address during the probe | the redirect is not followed (`ProbeTest`) |
| Private targets with `ALLOW_PRIVATE_TARGETS=false` | refused for 10.x, 127.x, 169.254.x, fc00::/7 and others (`TargetGuardTest`) |
| A sitemap with a DOCTYPE (XXE attempt) | refused by the XML parser (`ProbeTest`) |
| Container user | backend and mock run as uid 10001 (checked with `docker compose exec … id` on 16 Nov, earlier build) |
| Stop a run from the UI | stopped within seconds, partial results kept (19 requests, coverage note listing the untested cases) |

## Limitations of this evaluation

- **One machine.** The load generator and the target ran on the same laptop,
  so absolute latencies include its own load. The *shape* of the results (the
  bugs found, the plateau, the breaking point) is what is being checked.
- **The CI gate on GitHub's runners.** It is verified by running the same
  steps locally. The first real GitHub Actions run will confirm it on the hosted runners.
- **Docker** was not re-run for this round (the daemon was not running); the compose
  stack was last verified on 16 November with the earlier build.
- **Percentile resolution.** Split-run percentiles are exact merges, but each value is
  kept to HdrHistogram's 3 significant digits (±0.1%).
