# Elevate Load Tester — Run Guide

All paths below are relative to the repository root.

## Ways to run this

- **Docker compose** — one command, everything in containers (app, backend, mock target). Best for demos.
- **Simple mode** — backend and frontend from source; fewer moving parts, nothing to configure.
- **Queue mode** — **not deployed; future work.** Only for demonstrating the SQS/decoupled architecture locally.
- **CLI** — one load test from a config file, with a pass/fail exit code (for CI).

---

## Docker compose (one command)

From the repository root (Docker Desktop or Docker Engine with compose v2):
```
docker compose up --build
```
Then open `http://localhost:8080`. The app opens already filled in for the mock target
(`http://mock:8000/api/login` with the demo sample): click **Analyze**, then
**Generate edge cases**, tick the ownership box and start a run.

What runs:

| Service | What it is | Port on the host |
|---|---|---|
| `frontend` | nginx serving the built app; proxies `/api` to the backend | 8080 |
| `backend` | the Spring Boot API (Java 21), H2 database in a named volume | — (reach it through nginx on 8080) |
| `mock` | the Java mock target, listening on `0.0.0.0` inside the network | 8000 (optional) |

Inside compose the mock target is reached by its service name, `http://mock:8000`, which
resolves to a private address, so the backend runs with `ALLOW_PRIVATE_TARGETS=true`.
(Do not set that on a public deployment.) Stop everything with `docker compose down`
(add `-v` to also delete the stored runs).

---

## Simple Mode (recommended default)

**Terminal 1 — Backend:**
```
./mvnw -q package -DskipTests
java -jar backend/target/load-tester.jar
```

**Terminal 2 — Frontend:**
```
cd frontend
npm install
npm run dev
```

Open the printed local URL (usually `http://localhost:5173`). That's it — no environment
variables needed. Load tests run in the backend process, one virtual thread per simulated user.

For a repeatable offline target, start the mock login API in a third terminal:
`java -jar mock-target/target/mock-target-1.0.0-exec.jar` (listens on `http://127.0.0.1:8000/api/login`).

The `Makefile` has the same commands as targets (`make backend`, `make mock`, `make frontend`).

---

## Queue Mode (SQS) — not deployed, future work

The deployed backend runs load tests in-process (`USE_SQS=false`). Queue mode below works
locally for demonstrating the decoupled architecture, but no worker is deployed.

Requires 3 terminals. Same AWS values every time:
```
AWS_ACCESS_KEY_ID=<your current key>
AWS_SECRET_ACCESS_KEY=<your current secret>
AWS_DEFAULT_REGION=eu-north-1
SQS_QUEUE_URL=https://sqs.eu-north-1.amazonaws.com/<AWS_ACCOUNT_ID>/load-tester-jobs
```

(The real queue URL contains our AWS account ID — don't commit it. Keep it in the
`SQS_QUEUE_URL` environment variable / a local untracked `.env`.)

The backend and the worker share the H2 database file, so both need `H2_AUTO_SERVER=TRUE`
(the second process to open the file connects through the first).

**Terminal 1 — Backend (with SQS enabled):**

PowerShell:
```
$env:AWS_ACCESS_KEY_ID="..."
$env:AWS_SECRET_ACCESS_KEY="..."
$env:AWS_DEFAULT_REGION="eu-north-1"
$env:SQS_QUEUE_URL="https://sqs.eu-north-1.amazonaws.com/<AWS_ACCOUNT_ID>/load-tester-jobs"
$env:USE_SQS="true"
$env:H2_AUTO_SERVER="TRUE"
java -jar backend/target/load-tester.jar
```

bash:
```
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
export AWS_DEFAULT_REGION="eu-north-1"
export SQS_QUEUE_URL="https://sqs.eu-north-1.amazonaws.com/<AWS_ACCOUNT_ID>/load-tester-jobs"
export USE_SQS="true"
export H2_AUTO_SERVER="TRUE"
java -jar backend/target/load-tester.jar
```

**Terminal 2 — Worker:**

PowerShell:
```
$env:AWS_ACCESS_KEY_ID="..."
$env:AWS_SECRET_ACCESS_KEY="..."
$env:AWS_DEFAULT_REGION="eu-north-1"
$env:SQS_QUEUE_URL="https://sqs.eu-north-1.amazonaws.com/<AWS_ACCOUNT_ID>/load-tester-jobs"
$env:H2_AUTO_SERVER="TRUE"
java -jar backend/target/load-tester.jar worker
```

bash:
```
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
export AWS_DEFAULT_REGION="eu-north-1"
export SQS_QUEUE_URL="https://sqs.eu-north-1.amazonaws.com/<AWS_ACCOUNT_ID>/load-tester-jobs"
export H2_AUTO_SERVER="TRUE"
java -jar backend/target/load-tester.jar worker
```

Should log: `Worker started. Polling the queue for jobs...` (the worker always uses the queue;
`worker` switches queue mode on and starts no web server).

**Terminal 3 — Frontend (same as Simple Mode):**
```
cd frontend
npm run dev
```

**IMPORTANT:** In Queue Mode, if you skip setting `USE_SQS=true` in Terminal 1's environment
specifically, jobs silently run locally instead of going through SQS.

In queue mode, stopping a running test (`/api/cancel`), request headers and capacity runs are
refused with HTTP 409: the worker runs elsewhere, and the queue message would carry the header
values.

---

## Quick health check (either mode)
```
curl http://localhost:8080/health
```
Should return `{"status":"ok"}`.

---

## CLI (pass / fail for CI)

With a backend running (simple mode or compose):
```
java -jar backend/target/load-tester.jar run --config loadtest.example.yml
java -jar backend/target/load-tester.jar run --config loadtest.example.yml --users 30 --duration 20
java -jar backend/target/load-tester.jar run --config loadtest.example.yml --mode capacity --cases valid_sample
```
Exit codes: **0** SLO passed, **1** SLO failed, **2** error. The report, the JSON result and
`summary.md` go to `loadtest-results/` (or `output.dir` / `--out-dir`). Against the compose
stack, point the target at the mock's service name:
`--api http://localhost:8080 --url http://mock:8000/api/login`.

`loadtest.ci.yml` is the strict config the GitHub Actions performance gate runs.

Regression gate against the target's baseline (exit 0 no regressions, 1 regressions, 2 error):
```
java -jar backend/target/load-tester.jar gate --api http://localhost:8080 --url http://127.0.0.1:8000/api/login --sample scripts/gate_sample.json --i-own-target
```

Sitemap check for a website target:
```
java -jar backend/target/load-tester.jar check-sitemap --api http://localhost:8080 --url https://scentitude.in
```

---

## Tests
```
./mvnw verify
```
254 tests, no network and no running server needed (integration tests start the Java mock
on a random port). CI runs them on every push and pull request (`.github/workflows/ci.yml`),
together with frontend lint and build, the performance gate and, on `main`, the Docker image build.

---

## Known limitations (be upfront about these if asked)
- H2 is a single-node database: one API and one worker can share it with `AUTO_SERVER`, but
  several parallel workers would need a server database (PostgreSQL)
- The worker (`java -jar load-tester.jar worker`) is not yet deployed as a container/K3s pod —
  `backend/Dockerfile.worker` and `k8s/worker-deployment.yaml` exist and are ready, but the CI
  pipeline does not apply them; the deployed backend runs with `USE_SQS=false` (jobs run in-process)
- Split runs (above 50 users) run as parallel engine instances on one host; their latency
  histograms are merged exactly, so the merged p95 is exact (to HdrHistogram's 0.1% resolution)
- Only load-test targets you own or have permission for; every start request needs `i_own_target: true`
- Public test APIs (httpbin, jsonplaceholder) accept malformed input without validation — edge case
  failures only show meaningful signal against APIs with real validation (e.g. dummyjson.com/auth/login,
  or the local mock target)

---

## Reliable test targets
- **Local mock API (offline, repeatable):** `http://127.0.0.1:8000/api/login` (run
  `java -jar mock-target/target/mock-target-1.0.0-exec.jar`) — sample input:
  `{"username": "demo", "password": "demo123", "expiresInMins": 30}`
- **API with real validation:** `https://dummyjson.com/auth/login` — sample input: `{"username": "emilys", "password": "emilyspass"}`
- **Mock target inside docker compose:** `http://mock:8000/api/login` (same sample)
- **Website with no sitemap (fallback test):** `https://example.com` — only with a few users; it is not ours
