# Five-minute demo script

**Before the demo:** `docker compose up --build -d` and open http://localhost:8080, or from
source: `make mock`, `make backend`, `make frontend` and open http://localhost:5173. Wait until
the header says **Connected** (not "Backend offline"). Open a terminal in the repository root
for the CLI part.

The app has three views in the header: **New test**, **Run** and **History**.

## 0:00 — What it is (30 s)

"Elevate Load Tester checks two things at once: how fast an API is under load, and
whether it answers *correctly* when the input is wrong. The backend is Java 21 with our
own load engine — virtual threads, `java.net.http`, HdrHistogram. The target today is
our Java mock login API, which has three deliberate bugs."

## 0:30 — New test: target and cases (75 s)

1. **Target:** the URL is `http://127.0.0.1:8000/api/login` (in compose: `http://mock:8000/api/login`).
   Click **Analyze**: *Reachable, status 405, Looks like an API*.
   "We only probe with HEAD, GET and OPTIONS — never POST — and every redirect is checked
   against our SSRF guard." Tick **I own this target**.
2. **Sample request:** `{"username": "demo", "password": "demo123", "expiresInMins": 30}`.
   Click **Generate cases**: 27 cases in six groups, each with the request it sends and its
   **expected answer** (2xx or 4xx).
3. Switch `missing_expiresInMins` and `null_expiresInMins` to **2xx**: "this field is optional on
   the mock." Optionally open **Add your own case** (label `admin_login`, payload with another
   username) — it is checked against the sample before it is added.

## 1:45 — Load panel and run (60 s)

1. **Fixed load**, preset **Custom**: Users **15**, Start per second **15**, Seconds **15**. The panel
   shows how many requests that sends. SLO: p95 ≤ 500 ms, server errors ≤ 1%.
2. **Start load test** — the app switches to **Run**: live progress and a **Stop test** button.
   "15 virtual users, each a virtual thread; latencies go into a wait-free HdrHistogram
   recorder per case."
3. Results, findings first: **"3 cases found bugs"** —
   `very_long_string_username` *accepted invalid input (200)*, `wrong_type_expiresInMins` and
   `very_large_expiresInMins` *server error (500)*, each with the request that triggered it.
   Below: coverage, key figures, **SLO FAIL** (server errors), the case table, *Why did these fail?*
   and the timeline. Click **Mark as baseline**.

## 2:45 — Split run (45 s)

1. **Edit and re-run** with **60 users**, 8 seconds.
2. Point at the chip **"Split across 2 engine instances"** and the p95 note **"exact (merged
   histograms)"**: "HdrHistograms add up exactly, so the merged p95 is the true p95."
3. **Compared with the baseline**: the cases that got worse under load (rate limiting).

## 3:30 — History and compare (40 s)

Open **History**, tick the 15-user run (reference) and the 60-user run, click **Compare**:
the comparison scrolls into view and takes the focus — run totals and the cases that changed
with load. Tick another run: the old comparison disappears until you compare again.

## 4:10 — Capacity, CLI (50 s)

1. On **New test**, **Find capacity** with only `valid_sample` selected (20 → 200 users, 20 per 12 s
   step). It takes about 90 s; show a finished one from **History** instead if time is short:
   **max sustainable 120 users, breaking point 140** (p95 above 500 ms) — the mock's 8 workers.
2. CLI:

```bash
java -jar backend/target/load-tester.jar run --config loadtest.ci.yml
```

"Same jar, command-line mode: exit 0 on PASS, 1 on FAIL, 2 on error — that is our GitHub
Actions performance gate." (Under compose add `--api http://localhost:8080 --url http://mock:8000/api/login`.)

## Questions to be ready for

See [java-concepts.md](java-concepts.md) — 30 viva questions with answers — and
[evaluation.md](evaluation.md) for the measured results.
