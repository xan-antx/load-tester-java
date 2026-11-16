# Five-minute demo script

**Before the demo:** `docker compose up --build -d`, wait until the header of
http://localhost:8080 says "Connected" (not "Backend offline"). (Without Docker: start the mock, the backend jar
and `npm run dev` as in the README, and use http://localhost:5173.) Open a terminal in
the repository root for the CLI part.

## 0:00 — What it is (30 s)

"Elevate Load Tester checks two things at once: how fast an API is under load, and
whether it answers *correctly* when the input is wrong. The backend is Java 21 with our
own load engine — virtual threads, `java.net.http`, HdrHistogram. The target today is
our Java mock login API, which has three deliberate bugs."

## 0:30 — Analyze the target (30 s)

1. The URL is pre-filled: `http://mock:8000/api/login`. Click **Analyze**.
2. Point at the result: reachable, 405, "looks like **api**".
   "We only probe with HEAD, GET and OPTIONS — never POST — and every redirect is
   checked against our SSRF guard."

## 1:00 — Edge cases (45 s)

1. The sample `{"username": "demo", "password": "demo123", "expiresInMins": 30}` is
   pre-filled. Click **Generate edge cases**: 27 cases in 6 categories.
2. "Each kind is a Strategy class. Each case says what a correct API should answer:
   2xx or a 4xx rejection."
3. Click the **expects 4xx** tag on `missing_expiresInMins` and `null_expiresInMins` to
   flip them to 2xx: "this field is optional on the mock."

## 1:45 — Run with an SLO (75 s)

1. Users **15**, spawn rate **15**, duration **15** s. SLO: p95 ≤ 500 ms, server errors
   ≤ 1% (defaults). Tick **I own this target**, then **Confirm selection and start**.
2. While it runs: "15 virtual users, each a virtual thread; latencies go into a wait-free
   HdrHistogram recorder per case; a sampler builds the per-second timeline."
3. Result: **SLO FAIL**, p95 met, server errors missed. Scroll the per-case table:
   - `wrong_type_expiresInMins`, `very_large_expiresInMins` → **server error 500**
   - `very_long_string_username` → **accepted invalid input (200)**
   "Exactly the three bugs we planted, and nothing else flagged."
4. Show the timeline chart, then click **Mark as baseline**.

## 3:00 — Split run and comparison (45 s)

1. Set users to **60**, duration **8**, start again.
2. Point at the badge **"2 parallel engine instances"** and the p95 note **"exact (merged
   histograms)"**: "HdrHistograms add up exactly, so the merged p95 is the true p95."
3. The run is compared with the baseline automatically; then in **Compare runs** tick
   both runs and click **Compare**: per-case changes with load (here: rate limiting).

## 3:45 — Capacity finder (45 s, start it and talk)

1. Click **Select none**, tick only `valid_sample`, switch to **Find capacity** (20 → 200
   users, 20 per 12 s step), start.
2. "Each step is judged against the SLO on its own; with early stop the run ends at the
   first failing step." Result after about 90 s: **Max sustainable 120 users, breaking
   point 140** (p95 above 500 ms) — the mock has 8 workers, about 80 requests/s.
   (If time is short, show a capacity result from an earlier run in the history.)

## 4:30 — CLI and CI (30 s)

```bash
java -jar backend/target/load-tester.jar run --config loadtest.ci.yml --api http://localhost:8080 --duration 10
```

"The same jar is a CLI. It prints the verdict table and exits 0 on PASS, 1 on FAIL,
2 on error — that is our GitHub Actions performance gate." Show `perf-results/summary.md`.
(Under Docker the CI config's target `127.0.0.1:8000` is the mock's published port and
the backend runs in a container, so pass `--url http://mock:8000/api/login` instead.)

## Questions to be ready for

See [java-concepts.md](java-concepts.md) — 25 viva questions with answers.
