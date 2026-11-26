# Features explained

This page explains every feature added to Elevate Load Tester in the final
build, in plain English. For each one: what it does, why it exists, how it
works, which files it touches, how to demo it in under a minute, and two
questions a teacher might ask (with short answers).

Java paths are relative to `backend/src/main/java/com/elevate/` (tests:
`backend/src/test/java/com/elevate/`; mock target:
`mock-target/src/main/java/com/elevate/mock/`). Frontend paths are under
`frontend/src/`. Commands run from the repository root after
`./mvnw package -DskipTests`. Some **Files** lines name frontend components from before the UI revamp
(`JobResultSummary.jsx`, `Findings.jsx`, `StepRail.jsx`, …), as the Python document does; that
logic now lives in `views/`, `components/results/`, `components/newtest/` and `utils/runReport.js`.
Every figure on this page was measured on the Java build against the Java mock target.

## A few words first

- **Load test** — sending many requests to a server at the same time, to see
  how it behaves under pressure. We use our own load engine, built into the
  backend, to do this: virtual users on Java virtual threads, requests sent
  with `java.net.http`, response times recorded in HdrHistograms.
- **Virtual user** — one simulated person sending requests in a loop. "150
  users" means 150 of these running at once.
- **Edge case** — an unusual input, such as a missing field, a `null`
  value or a huge number. Good servers reject bad input cleanly; weak servers
  crash.
- **HTTP status code** — the three-digit number a server sends back with
  every answer. 200 = OK. 4xx (like 400) = "your request was wrong". 429 = "too
  many requests, slow down". 5xx (like 500) = "the server itself broke".
- **CSV** — a plain text table, one row per line, columns separated by
  commas. The backend sends its results as CSV text in Locust's formats, which
  the frontend reads.
- **Endpoint** — one URL of our backend that the frontend calls, for example
  `/api/compare-jobs`.
- **Split run** — a run with more than 50 users runs as several engine
  instances **in parallel on the same host**: one per 50 users, at most 10 (so
  150 users become 3 × 50, and 1,000 users become 10 × 100). Their results are
  merged into one. The Java backend adds their latency histograms, so the
  merged p95 is exact and the response says `p95_exact: true`; against the
  Python backend the same UI marks the merged p95 as an approximation (≈).
- **Percentile (p95)** — sort all response times from fastest to slowest; the
  p95 is the time that 95% of requests beat. It shows the slow tail that an
  average hides.
- **Request header** — a named extra line sent with a request, such as
  `Authorization: Bearer <token>`, which proves who is calling.
- **Exit code** — the number a command-line program returns when it ends; 0
  means success. CI pipelines pass or fail a step on it.
- **SLO (service-level objective)** — a promise about performance, such as
  "95% of requests answer within 500 ms". A run either meets it or not.
- **Capacity** — the most users a server can handle while still meeting its SLO.
- **SSRF (server-side request forgery)** — tricking a server into sending
  requests to places only it can reach, such as its own private network.
- **Standard deviation (SD)** — how far values typically sit from their mean.
  "11.03 ± 0.29 req/s"
  means the repeats were very close to each other.

---

## 1. Local mock target

**What it does.** A tiny fake login API (`POST /api/login` on
`127.0.0.1:8000`) that we can load-test instead of a real website.

**Why it exists.** Public APIs change, rate-limit us unpredictably and need
internet. A local target gives the same results every time, works offline, and
we know exactly how it should behave.

**How it works.** It accepts `demo` / `demo123`. Bad usernames or passwords
get 400. The optional `expiresInMins` field is handled carefully for missing,
null, 0 and negative values, but deliberately *not* for text or huge numbers.
Those cause a crash (500), like a real server with a missing check. A rate limiter
returns 429 once more than 40 requests arrive in the same second. It is plain
Java (`com.sun.net.httpserver`), with no Spring.

**Files.** The `mock-target` module: `MockTargetServer.java`,
`LoginHandler.java`, `LoginRules.java`, `FixedWindowRateLimiter.java`,
`MockConfig.java`.

**Demo (≈1 min).** Run `java -jar mock-target/target/mock-target-1.0.0-exec.jar`
in the repository root. In the app, analyze `http://127.0.0.1:8000/api/login`
with sample `{"username": "demo", "password": "demo123", "expiresInMins": 30}`,
generate cases, and run 15 users for 30 s.

**Teacher might ask.**
- *Why build a fake server instead of testing a real one?* — So results are
  repeatable and we never put heavy load on someone else's server. A test you
  can't repeat can't be trusted.
- *Why does it crash on purpose?* — To prove the tool can tell "server rejected
  bad input" (400) apart from "server broke" (500). The second one is a real
  bug a developer would want to know about.

---

## 2. Failure types (instead of 32 "distinct errors")

**What it does.** Under "Why did these fail?", failures are grouped into a few
types by status code: **Rejected — invalid input** (4xx), **Rate limited** (429),
**Server error** (5xx), and **Connection failure** (no answer at all). Each
card shows a coloured code badge, the total, and which cases hit it.

**Why it exists.** The raw failures list (Locust's failures CSV format) has the
case name inside every error message, so
27 cases looked like
32
different errors. Grouping by status code shows what actually happened.

**How it works.** The engine records every failed request with its outcome and
status code as structured data, not as text. `FailureSummarizer` adds up the
counts per outcome and code, so no error text has to be parsed. The old raw
list is still there behind "Show raw errors".

**Files.** `frontend/src/theme.js` (the colour for each failure type),
`analysis/outcome/FailureSummarizer.java`, `FailureGroup.java`,
`ResponseClassifier.java` (`meaningOf`), `service/RunAnalyzer.java` (adds a
`failure_summary` field), `frontend/src/components/JobResultSummary.jsx`,
test `analysis/outcome/OutcomeClassificationTest.java`
(`failuresAreGroupedByKindAndStatusLargestFirst`).

**Demo.** After a 150-user mock run, open "Why did these fail?": you see three
cards (429 blue, 400 amber, 500 red). Their totals add up to the failure count
on the Failure Rate card.

**Teacher might ask.**
- *Why is 429 separate from the other 4xx codes?* — 400 means the input was
  bad; 429 means the input might be fine but we sent too much too fast. They
  need different fixes.
- *What if an error has no status code?* — It goes into "Connection failure",
  for example when the server refused the connection or timed out.

---

## 3. p95 response time

**What it does.** Adds a "p95 Response" card and a p95 column next to the
average.

**Why it exists.** An average can look fine while 1 in 20 users waits a long
time. p95 shows that slow tail.

**How it works.** The engine records every response time in an HdrHistogram
per case, and the backend writes the percentiles into the stats CSV; we
display the "95%" column. For split runs the Java backend adds the children's
histograms, so the merged p95 is the true percentile of all requests, and the
response carries `p95_exact: true`. The UI then shows no "≈" and says "exact
(merged histograms)". Against the Python backend, whose split-run p95 is a
request-weighted average of each parallel Locust process's p95, the same UI
marks it "≈" in every place that shows a split run's p95: the p95 card, the
"≈ p95 ms" column, the timeline legend, the SLO check's measured value, the
Compare table and the Markdown report. The CLI's report says "histograms
merged, p95 exact".

**Files.** `JobResultSummary.jsx`, `SloVerdict.jsx`, `Timeline.jsx`,
`CompareView.jsx`, `utils/report.js`, `cli/CliOutputs.java`,
`engine/CaseRecorder.java`, `analysis/stats/ResultMerger.java` and
`Histograms.java` (the exact merge), `dto/GroupView.java` (`p95_exact`).

**Demo.** Point at the p95 card after any run. On a 150-user run it shows
"… ms, exact (merged histograms)"; hover it to read why.

**Teacher might ask.**
- *How can percentiles be merged exactly, when the Python version could not?*
  — You need every single response time from every engine instance, or a
  histogram of them. Each engine instance keeps an HdrHistogram, and adding
  histograms gives the histogram of all requests together. Each Locust process
  only reported its own summary, and averaging summaries gives a close
  estimate, not the true value.
- *Why p95 and not the maximum?* — The maximum is one unlucky request; p95
  describes what a meaningful share of users experience.

---

## 4. Local timestamps

**What it does.** Run times show in your own time zone (e.g. 10:42 PM) instead
of the server's UTC time.

**Why it exists.** The backend sends times in UTC without saying so (the Java
backend keeps the format of the Python version's SQLite timestamps,
`yyyy-MM-dd HH:mm:ss`, in `dto/Timestamps.java`), which made times look hours
off.

**How it works.** `formatTime()` adds a "Z" (meaning UTC) before parsing, so
the browser converts it to local time. Sorting still uses the original text,
so the order never changes.

**Files.** `frontend/src/utils/formatTime.js`, `App.jsx`, `CompareView.jsx`.

**Demo.** Look at the run list in "Compare past runs".

**Teacher might ask.**
- *Why not store local time in the database?* — Servers and users can be in
  different time zones; storing UTC and converting only for display is the
  standard practice.
- *What if the timestamp is broken?* — The function shows the original text
  instead of crashing.

---

## 5. Website mode labels

**What it does.** For website runs the first column says **Page** (not "Edge
Case"), and the Flat/Grouped toggle is hidden.

**Why it exists.** Website tests visit page paths like `/about`; those aren't
edge cases and don't belong to edge-case categories.

**How it works.** A run is a website run if the group says
`job_type = "website"`, or if every row name starts with `/`. Page paths
always start with `/`, while edge-case labels never do.

**Files.** `JobResultSummary.jsx`.

**Demo.** Analyze `https://example.com` and run 2 users for 5 s.

**Teacher might ask.**
- *Why check names starting with "/" instead of storing the type?* — The
  Python backend's single (non-split) jobs never stored their type. The Java
  backend sends `job_type` on every job, but the same rule works for both
  backends and for old runs, without changing the database.
- *Could an edge-case label start with "/"?* — No. The generator builds labels
  like `missing_username`, and custom labels may only use lowercase letters,
  digits and `_`.

---

## 6. Merge bug fix (one "Aggregated" row)

**What it does.** Makes sure split runs have exactly one total row, never a
second, double-counted one.

**Why it exists.** In the Python version, each child job's own total was
merged like an ordinary case, then a new total was added on top. The second
total was exactly double. The Java merge was written with the fix in place.

**How it works.** `ResultMerger` merges only the children's per-case
measurements (counts, sums, histograms); `StatsTable` then builds one fresh
total from the per-case rows. Unit tests check the merge.

**Files.** `analysis/stats/ResultMerger.java`, `analysis/stats/StatsTable.java`,
test `analysis/stats/StatsTest.java` (`statsTableHasRowsAndAnAggregatedTotal`,
`mergedPercentilesAreExact`).

**Demo.** Not visible in the UI (the UI already read the first, correct row).
Show the test: `./mvnw test -Dtest=StatsTest -Dsurefire.failIfNoSpecifiedTests=false`.

**Teacher might ask.**
- *How did you find it?* — In the Python version, while reading the merge
  code; it was then confirmed on a real 150-user run in the database, where
  the second total row counted exactly twice the requests of the first.
- *Why didn't users notice?* — The frontend happened to use the first total row,
  which was correct. New features that read "the total" could have picked the
  wrong one.

---

## 7. Compare 2 to 4 runs

**What it does.** Tick 2–4 past runs (single or split) and compare them in one
table. Every run after the first shows a ▲/▼ percentage against Run 1.

**Why it exists.** Real questions need more than two runs, e.g. "15, 50 and 150
users — where does it break?"

**How it works.** The frontend sends `{"job_ids": [...]}` to
`/api/compare-jobs`; the backend returns the runs in the same order. The old
two-run form still works. Colours depend on meaning, not direction: more
throughput is green, but more failures or slower responses are red.

**Files.** `controller/HistoryController.java`, `service/RunViewService.java`
(`compare`), `App.jsx` (checklist), `CompareView.jsx`,
test `controller/HistoryAndBaselineControllerTest.java` (`compareBothForms`).

**Demo.** In "Compare past runs" tick the 15-, 50- and 150-user runs, press
Compare.

**Teacher might ask.**
- *Why limit it to 4?* — More columns stop fitting on screen and become hard to
  read; four covers low/medium/high/extreme load.
- *Why is "up" sometimes green and sometimes red?* — Up is good for requests
  per second but bad for failures or latency, so the colour follows meaning.

---

## 8. Per-case comparison ("Changed with load")

**What it does.** Below the totals, a table shows each case's failure rate in
each run. Cases whose failure rate changes by 10 points or more are highlighted
at the top under **Changed with load**.

**Why it exists.** Most cases fail 100% at every load level, so the overall
failure rate barely moves. That hides the real finding: a few cases go from 0%
to about 100%
only under heavy load.

**How it works.** For each case and run we compute failure % and request count.
Cells with fewer than 5 requests are a **low sample** (too few to trust); they
are faded and ignored. A case is "changed" if any two trusted cells differ by
10 points or more.

**Files.** `analysis/compare/CaseComparison.java`, `service/RunViewService.java`,
`CompareView.jsx`, test `analysis/compare/CompareTest.java`.

**Demo.** Compare a 15-user and a 150-user mock run (30 s each). The three
`expiresInMins` cases (missing / null / zero) appear under "Changed with load";
the login cases don't.

**Teacher might ask.**
- *Why ignore cells with fewer than 5 requests?* — With 2 requests, one
  failure is already 50%; that's noise, not a finding.
- *Why did the tolerated cases start failing?* — They weren't rejected for being
  bad input; they were rate-limited (429) because the server got more requests
  than it allows per second.

---

## 9. Timeline charts

**What it does.** Three small charts on the same time axis: active users,
throughput (requests/s and failures/s), and response time (average and p95).
Hover to read the values at any second; there is also a data-table view.

**Why it exists.** Totals don't show *when* things went wrong. The timeline
shows the ramp-up and the moment the server starts to struggle.

**How it works.** Once a second the engine's `IntervalSampler` drains each
case's histogram and stores one sample for that second (users, requests,
failures, latencies). The backend sends the samples as one summary row per
second in Locust's `stats_history.csv` format. For split runs the children's
samples for the same second are added together, histograms included, so the
merged per-second p95 is exact. The charts are drawn as SVG (vector graphics)
in plain React, with no chart library.

**Files.** `engine/IntervalSampler.java`, `model/TimelinePoint.java`,
`analysis/stats/TimelineSample.java`, `analysis/stats/ResultMerger.java`,
`analysis/stats/LocustCsv.java` (`history`),
`frontend/src/components/Timeline.jsx`, test `analysis/stats/StatsTest.java`
(`mergeAddsFailuresAndAlignsTimelineSeconds`,
`historyCsvHasRunningTotalsAndWindowedPercentiles`).

**Demo.** After a 150-user run, scroll to "Timeline": users climb to 150, and
failures/s rise to almost meet requests/s once rate limiting starts.

**Teacher might ask.**
- *Why three charts instead of one with two y-axes?* — Users and requests/s
  have different units; a two-axis chart lets you make lines look related or
  unrelated just by scaling. Separate charts sharing a time axis are honest.
- *Why does p95 move more than the average?* — The backend's average is a
  running average since the start (as Locust's is), so it changes slowly; p95
  is over the last 5 seconds, so it reacts quickly.

---

## 10. Custom edge cases

**What it does.** Add your own test case (a label + a JSON body). The system
checks it's a real edge case before adding it to the list.

**Why it exists.** Generated cases can't cover everything. For example, "log in
as admin" is a test only the tester would think of.

**How it works.** The backend compares your JSON with the sample field by
field: removed fields, nulls, type changes, value changes, added fields. It
rejects a case that is identical to the sample, isn't a JSON object, or has a
bad or already-used label. When you start the test, the backend checks every
custom case again (it never trusts the browser alone).

**Files.** `analysis/edgecase/CustomCaseClassifier.java`,
`analysis/edgecase/CustomCaseResult.java`, `controller/EdgeCaseController.java`
and `service/EdgeCaseService.java` (`/api/validate-custom-case`, `custom_cases`
in `/api/confirm-selection`), `App.jsx`,
tests `analysis/edgecase/CustomCaseClassifierTest.java`,
`controller/EdgeCaseControllerTest.java`, `service/EdgeCaseServiceTest.java`.

**Demo.** In section 3 open "Add your own case": label `admin_user`, body with
`"username": "admin"` → added with a [custom] tag. Try the sample unchanged →
"Not an edge case — identical to the sample". Try broken JSON → error.

**Teacher might ask.**
- *Why validate twice, in the browser and on the server?* — Anyone can send
  requests to the backend directly, bypassing the browser. The server must check
  for itself.
- *Why compare field by field instead of comparing the values?* — Compared as
  numbers, `30` and `30.0` are equal (and Python even says `1 == True`), but to
  an API those are different types. Our diff checks each field's JSON type
  first (`FieldType`: int, float, bool, str …), so it catches them; it also
  treats an int and a long with the same value as equal, which Jackson's
  `equals` does not.

---

## 11. Baseline and regression flags

**What it does.** Mark a completed run as the **baseline** (the known-good
reference) for its target. Later runs of the same target show "Compared with
baseline": cases whose failure rate rose by 10+ points, and how much p95
changed.

**Why it exists.** This is the core idea of **regression testing**: after a
change, automatically check that nothing got worse than before.

**How it works.** One run per target carries a baseline flag in the database.
When a run finishes, the frontend asks `/api/regressions/<id>`. The backend
compares it with the baseline using the same rules as the per-case comparison
(at least 5 requests on both sides).

**Files.** `analysis/compare/RegressionReport.java`, `model/MeasuredRun.java`
(the baseline flag), `service/BaselineService.java`,
`controller/BaselineController.java`,
`frontend/src/components/RunActions.jsx`, `App.jsx`,
tests `analysis/compare/CompareTest.java`
(`regressionsAreCasesThatGotWorseByTenPoints`),
`controller/HistoryAndBaselineControllerTest.java`.

**Demo.** Run 15 users for 30 s → "Mark as baseline". Run 150 users for 30 s → the
panel lists the three `expiresInMins` cases and the p95 change.

**Teacher might ask.**
- *Why only one baseline per target?* — Then "compared with the baseline" always
  has one clear meaning; marking a new one replaces the old.
- *What counts as "the same target"?* — The stored target, which is the host
  (e.g. `http://127.0.0.1:8000`).

---

## 12. Downloadable report

**What it does.** "Download report (.md)" saves a Markdown file with the
target, settings, summary, failure types, per-case table, baseline comparison
and the time it was made.

**Why it exists.** Results need to be shared or attached to a bug report, not
just looked at on screen.

**How it works.** The browser builds the text from data it already has and
saves it with a **Blob** (an in-memory file) and a temporary download link.
The backend isn't involved. (The Java backend also serves a Markdown report at
`GET /api/report/{id}` for the CLI and CI; the download button does not use it.)

**Files.** `frontend/src/utils/report.js`, `RunActions.jsx`.

**Demo.** After any completed run, click "Download report (.md)" and open the
file.

**Teacher might ask.**
- *Why Markdown?* — It's readable as plain text and renders nicely on GitHub
  and GitLab.
- *Why build it in the browser?* — Everything needed is already on the page, so
  no new server code is needed.

---

## 13. Summary of results and key figures

**What it does.** At the top of every completed result, a "Summary of results"
gives two to four numbered sentences saying what happened, for example "2 cases
caused server errors (500) on 57
requests", "Rate limiting (429) on 562
requests across 27 cases" or
"15
cases got worse than the baseline run". Each
sentence starts with a small square in its status colour. Below it, a row of
key figures (requests, failure rate with
"42 of 171"
underneath, average and p95 response, requests per second, duration) is
separated by thin vertical dividers; on a phone the row becomes two columns.

**Why it exists.** A results page full of numbers takes time to read. The
findings give the conclusion first, so a viewer knows what to look for.

**How it works.** A small pure function (one with no side effects: same input,
same output) reads data the page already has — the failure types, the per-case
rows and the baseline comparison — and turns it into sentences, most serious
first. No backend change.

**Files.** `frontend/src/utils/findings.js`,
`frontend/src/components/Findings.jsx`, `JobResultSummary.jsx` (the
key-figure row), `frontend/src/index.css` (`.stat-row`: the dividers and
the two-column phone layout).

**Demo.** Finish a 150-user mock run after marking a 15-user run as the
baseline: the findings list server errors, the regressions, rate limiting and
rejected input.

**Teacher might ask.**
- *Where do these sentences come from — is it AI?* — No. They are built by
  fixed rules from numbers the backend already returned, so they are always
  consistent with the tables below.
- *Why at most four?* — More than four stops being a summary.

---

## 14. Outcome strips

**What it does.** A coloured bar shows how requests ended: green passed, amber
rejected (4xx), blue rate limited (429), red server error (5xx), grey
connection failure. There is one large strip for the whole run, a small one in
every row of the results table, and one per run in the comparison.

**Why it exists.** It makes the main finding visible at a glance: at 150 users
the tolerated `expiresInMins` cases turn from all green to green plus blue,
because rate limiting starts.

**How it works.** The failure types already say, for each case, how many
requests failed with which status. Whatever didn't fail counts as passed. The
bar's segment widths are proportional to those counts.

**Files.** `frontend/src/utils/outcomes.js`,
`frontend/src/components/OutcomeStrip.jsx`, `JobResultSummary.jsx`,
`CompareView.jsx`, `frontend/src/theme.js` (the `outcome` colours).

**Demo.** Scroll the results table of a 150-user run: credential cases are
amber and blue, the three `expiresInMins` cases green and blue, and the two
crashing cases red and blue.

**Teacher might ask.**
- *Why these colours?* — Each colour means exactly one outcome everywhere in
  the app, and is never used for decoration, so the reader learns it once.
- *Is colour the only signal?* — No. Every strip has a text description for
  screen readers, a tooltip, and the large one has a legend with counts.

---

## 15. Backend connection indicator

**What it does.** The header shows "Connected" (green) or "Backend offline"
(red).

**Why it exists.** If the backend isn't running, nothing works. The indicator
says so immediately instead of letting the user find out from an error.

**How it works.** Every 15 seconds the page calls the existing `GET
/api/health` endpoint. An answer means connected; no answer means offline.

**Files.** `frontend/src/components/AppHeader.jsx`.

**Demo.** Stop the backend (`java -jar backend/target/load-tester.jar`, Ctrl+C);
within 15 seconds the header says "Backend offline". Start it again and it
returns to "Connected".

**Teacher might ask.**
- *Why 15 seconds and not every second?* — It is only a status light; checking
  often would add load for no benefit.
- *Does it change how tests run?* — No, it only reads the health endpoint.

---

## 16. Visual design and step rail

**What it does.** A consistent dark "measurement instrument" look: graphite
panels, one teal accent for actions, status colours only for meaning, a
monospace font for every number. The left step rail shows each step's state
(done, current, waiting, not needed) and scrolls to it when clicked.

**Why it exists.** A consistent visual language makes the tool feel like real
engineering software and makes the results easier to read.

**How it works.** All colours, fonts, sizes, spacing and motion timings are
design tokens (named values) in one file, `theme.js`; components only use those
names. At start-up `main.jsx` publishes the same tokens as CSS variables, so
the stylesheet uses them too and contains no colour values of its own. Buttons
and clickable rows are styled by CSS classes (`btn`, `btn-primary`, `toggle`,
`row-interactive` …) because only CSS can give them hover, pressed and
disabled states. There are five text sizes and one 4px spacing scale. Motion
is limited to short feedback and the progress bar, which moves with
`transform` rather than resizing. The fonts are stored in the project
(`public/fonts`), so the app works without internet. The step rail used to
live inside `App.jsx`; it is now its own component.

**Files.** `frontend/src/theme.js` (tokens and `applyTheme`),
`frontend/src/main.jsx` (calls `applyTheme`),
`frontend/src/index.css` (fonts, button and row states, layout,
scrollbars, reduced motion), `frontend/public/fonts/`,
`frontend/src/components/Section.jsx` (step panels and shared input and
table styles; button styles moved to `index.css`),
`frontend/src/components/StepRail.jsx` (moved out of `App.jsx`), `App.jsx`.

**Demo.** Watch the rail while doing the workflow: each step goes from
"Current step" to "Done"; for a website target, steps 2 and 3 show "Not
needed". Narrow the window: the rail becomes a bar at the top.

**Teacher might ask.**
- *Why keep all colours in one file?* — Changing the look means editing one
  file, and it guarantees that, for example, "red" always means the same thing.
- *Is it accessible?* — Every input has a label, everything works with the
  keyboard, focus is always visible, and animations are switched off for
  people who ask their system for reduced motion.

---

## 17. Problem rate

**What it does.** A second headline number next to the failure rate:
"Problem rate" = (server errors 5xx + rate limiting 429 + connection failures)
÷ all requests. It also appears in the comparison table and the report.

**Why it exists.** Our evaluation listed this limitation: the failure rate
counts *every* non-2xx answer as a failure, including the 400s a correct API
*should* return for invalid edge cases. A well-behaved API therefore showed an
alarming "24.6% failure rate". The
problem rate counts only answers that point at a real problem.

**How it works.** The backend already groups failures by type (feature 2).
A small function (`OutcomeAnalysis.problemRate`) adds up the server-error,
rate-limited and connection groups and divides by the total request count
from the stats. It is added to every status and compare response as
`problem_count` and `problem_rate`; the failure rate is unchanged.

**Files.** `analysis/outcome/OutcomeAnalysis.java`, `service/RunAnalyzer.java`,
`dto/RunAnalysis.java`, `JobResultSummary.jsx`, `CompareView.jsx`,
`utils/report.js`, test `analysis/outcome/OutcomeClassificationTest.java`
(`problemRateCountsOnly5xx429AndConnection`).

**Demo.** Run the mock target with 15 users for 15 s: Failure rate ≈
24.6%, Problem rate ≈
11.1% (only the
500s). Hover the Problem rate for the explanation.

**Teacher might ask.**
- *Why not just change the failure rate?* — Other features (comparison,
  baseline regressions, reports) rely on it and it is the standard
  load-testing number (Locust's failure count); adding a second number is
  safer and shows both views.
- *Why count 429 as a problem?* — The request itself was fine; the server
  refused it because of load, which is exactly what a load test looks for.

---

## 18. Stop a running test

**What it does.** A "Stop test" button beside the progress bar, with a
confirm step ("Stop now? Results so far are kept."). The run ends as
**cancelled**; if the engine had already recorded requests, they are kept and
labelled "Cancelled — partial results".

**Why it exists.** Listed limitation (UX review U13): a mistaken 300-second
run could not be stopped and blocked every other test.

**How it works.** Every running job's `LoadEngine` is kept in `RunRegistry`,
in memory, by job id. `POST /api/cancel/<id>` stops that engine — or every
child engine of a split run, or the current repeat of a series, after which no
more repeats start. `LoadEngine.stop()` ends the run at once: the virtual
users are interrupted, and the engine returns the measurements recorded so
far, which are stored with status `cancelled`. A job still queued becomes
`cancelled` without running. In queue (SQS) mode, which is not deployed
(future work), it returns 409, because the worker would run on another
machine.

**Files.** `service/RunExecutor.java` (`cancel`), `service/RunRegistry.java`,
`engine/LoadEngine.java` (`stop`), `service/JobRunner.java`,
`controller/LoadTestController.java`, `App.jsx`, `JobResultSummary.jsx`,
`CompareView.jsx`, `utils/report.js`,
tests `service/RunControlTest.java` (`cancelDependsOnWhatTheIdNames`),
`engine/LoadEngineTest.java` (`stopEndsTheRunQuicklyAndLeavesNoUsersRunning`),
`it/ApiAgainstMockIT.java` (`cancelKeepsPartialResults`).

**Demo.** Start a 30 s run, click "Stop test" → "Yes, stop" after 10 s. Within
a second the status is "Cancelled" and
all 111
of the requests are shown as partial results. The run appears as "cancelled"
in the history.

**Teacher might ask.**
- *How can one request stop a run that another request started?* — The
  engine runs in the backend process, and `RunRegistry` maps each job id to
  its engine object. `stop()` only sets a flag and interrupts the users'
  virtual threads, so the thread handling the cancel request can call it
  safely.
- *What happens to a split run?* — Every child is stopped; the group becomes
  "cancelled" once all children have stopped, and their partial results are
  merged exactly like a finished split run.

---

## 19. Lost connection while polling

**What it does.** If the backend stops answering during a run, a banner says
"Lost connection to the backend — retrying" and the app keeps checking every
2 seconds. When the backend answers again the banner disappears. After about
30 seconds (15 failed checks) it shows "Status unknown — the backend is
unreachable" with a "Check again" button.

**Why it exists.** Listed limitation (UX review U17): one failed status check
silently stopped the polling, so the page said "Running" forever.

**How it works.** Error handling was added *inside* `pollStatus()` only: a
`try/catch` around the status request, a counter of consecutive failures, and
a small `pollState` value (`retrying` or `lost`) that drives the banner. The
2-second interval and the completion check are unchanged. The double-start
guard is released only in the final "lost" state.

**Files.** `frontend/src/App.jsx`.

**Demo.** Start a 30 s run and stop the backend (Ctrl+C): the retrying banner
appears; after ~30 s, "Status unknown" and "Check again". (Restarting the
backend ends that run as "interrupted", because runs live in the backend
process.)

**Teacher might ask.**
- *Why 15 attempts?* — 15 × 2 s ≈ 30 s: long enough to survive a backend
  restart or a short network blip, short enough not to leave the user waiting.
- *Why release the guard only at the end?* — While retrying, the run may still
  be going; starting a second one would make two polling loops overwrite each
  other.

---

## 20. Request headers (authenticated APIs)

**What it does.** Step 2 has an optional "Request headers" editor (name/value
rows, Add / Remove), e.g. `Authorization: Bearer <token>`. The headers are
sent with the Analyze probe and with every request the load engine makes, for
API and website tests, single and split runs.

**Why it exists.** Most real APIs need a token; without headers every request
got 401 and the tool could only test public endpoints.

**How it works.** The browser sends the headers with the start request. The
backend checks them (valid names, single-line values; headers that
`java.net.http` sets itself, such as `Host` or `Content-Length`, are refused
with a 400) and hands the values to the engine **only in memory**: a
`RequestHeaders` object goes from the start request to the run's thread and
into the engine's request templates. Values are never written to the
database, a file or a log, and never sent back: only the header *names* are
stored with the run, and any error message stored with a run is passed
through a redaction step. Values are typed into password fields, so they are
masked on screen. In SQS queue mode (not deployed) headers are refused with
409, since the queue would store them. The mock target can require a token
with `MOCK_REQUIRE_TOKEN=demo-token`.

**Files.** `analysis/headers/RequestHeaders.java`,
`analysis/probe/ReachabilityProbe.java`, `controller/LoadTestController.java`,
`service/LoadTestService.java`, `service/RunExecutor.java`,
`service/EngineSpecFactory.java`, `model/RunSettings.java` (`header_names`),
the mock's `MockConfig.java` and `LoginHandler.java`,
`components/RequestHeadersEditor.jsx`, `App.jsx`, `utils/report.js`,
`analysis/outcome/ResponseClassifier.java` (`meaningOf`) and
`utils/findings.js` (401/403 explained as "not authorised"),
test `analysis/headers/RequestHeadersTest.java`.

**Demo.** Start the mock with `MOCK_REQUIRE_TOKEN=demo-token`. Run 15 users
for 10 s: every request is a 401 ("refused as not authorised; check the
request headers"). Add `Authorization` = `Bearer demo-token` in step 2 and run
again: normal results, and "Sent with request headers: Authorization (values
hidden)".

**Teacher might ask.**
- *How do you know the token is not stored?* — The values never reach the
  database: the stored settings hold only the header names
  (`RunSettings.headerNames`), and the values live in a `RequestHeaders`
  object in memory. Error messages stored with a run go through
  `RequestHeaders.redact`, which `RequestHeadersTest` checks.
- *Why keep the values in memory and not in the database, like the cases?* —
  The database would put the secret on disk, and it would have to be deleted
  reliably even when a run crashes. The engine runs inside the backend
  process, so the values only exist in its memory and disappear with the run.

---

## 21. CI regression gate

**What it does.** `java -jar backend/target/load-tester.jar gate` is a
command-line check for a CI pipeline: it starts a load test through the normal
API, waits for it, compares it with the baseline and exits **0** (no
regressions), **1** (regressions) or **2** (error). An example GitLab job runs
it manually.

**Why it exists.** Regression detection (feature 11) only helped someone who
opened the app. A pipeline needs a yes/no answer it can act on automatically.

**How it works.** The command generates and confirms all edge cases from a
sample file, starts the run, polls the status, then calls
`/api/regressions/<run>` (optionally `?baseline_id=<id>`, a new additive
parameter) and prints a short summary. The decision is a pure function of
that response, so it is tested without a network. The CI job is
`when: manual` and `allow_failure: true`, so it never runs or blocks anything
by itself.

**Files.** `cli/GateCommand.java`, `scripts/gate_sample.json`, `RUNBOOK.md`,
`.gitlab-ci.yml`, `controller/BaselineController.java` (`baseline_id`
parameter), test `cli/GateCommandTest.java`.

**Demo.** Mark a 15-user run as baseline in the app, then in the repository
root:
`java -jar backend/target/load-tester.jar gate --api http://localhost:8080 --url
http://127.0.0.1:8000/api/login --sample scripts/gate_sample.json --users 150
--duration 15 --i-own-target` → "FAIL: 15
case(s) got worse…", and `echo $?` (or `$LASTEXITCODE` in PowerShell) prints 1.
With `--users 15` it prints PASS and exits 0.

**Teacher might ask.**
- *Why exit codes?* — Every CI system treats exit code 0 as success and
  anything else as failure; that is the universal way for a script to pass or
  fail a pipeline step.
- *Why is the CI job manual?* — A load test needs a running backend and a
  target we own, and it puts real load on it; it should never fire on every
  commit without someone deciding to.

---

## 22. Repeated runs (series)

**What it does.** "Repeat" (1–5) in the load settings runs the same test that
many times back to back. When they finish, a series summary shows, for the
whole run, the mean ± standard deviation of throughput, p95 and problem rate,
and for each case its mean failure rate and min–max across repeats.

**Why it exists.** One load test is one sample: run it again and the numbers
move. Without repeats we could not tell whether a difference between two runs
was real or just noise.

**How it works.** The backend stores a *series* (its settings and the ids of
its runs) and a background virtual thread starts one ordinary run (single or
split), waits for it to finish, and starts the next. Each repeat is a normal
run, so history, comparison and baselines work on it unchanged. The series id
is polled through the usual status endpoint; a pure function computes the
statistics. Stop cancels the current repeat and starts no more; a series
counts as one running test for the double-start guard.

**Files.** `analysis/compare/SeriesSummary.java`, `service/LoadTestService.java`
(`repeat`), `service/RunExecutor.java` (`launchSeries`, `executeSeries`),
`model/RepeatSeries.java` (the series table), `dto/SeriesView.java`,
`LoadTestConfig.jsx`, `components/SeriesSummary.jsx`, `App.jsx`,
tests `analysis/compare/CompareTest.java` (`seriesSummaryGivesMeanStdMinMax`),
`service/LoadTestServiceTest.java` (`repeatStartsASeries`),
`it/ApiAgainstMockIT.java` (`seriesRunsBackToBack`).

**Demo.** Mock target, 15 users, 10 s, Repeat 3 → after
31 s: throughput
≈ 11.03 ± 0.29 req/s,
p95 ≈ 148.78 ± 1.12 ms,
and a per-case table where the `expiresInMins` cases show their min–max spread.

**Teacher might ask.**
- *Why the sample standard deviation (n − 1)?* — Three repeats are a sample
  of all the runs we could have done; dividing by n − 1 corrects the
  tendency of a small sample to underestimate the spread.
- *Why one after another instead of in parallel?* — Parallel repeats would
  load the server together and change the result; the point is to repeat the
  *same* conditions.

---

## 23. Safe defaults and target guardrails

**What it does.** Four protections against misuse:

- **No debug mode.** The Spring Boot backend has no interactive debugger to
  switch on, and error responses carry only an error sentence, never a stack
  trace (`GlobalExceptionHandler`). It listens on `PORT` (8080) on every
  interface, Spring Boot's default.
- **Production setup.** The container runs the backend jar on a Java 21 JRE
  as an unprivileged user (uid 10001), not as root.
- **Blocked addresses.** The backend refuses targets that resolve to private,
  loopback, link-local, multicast or reserved addresses unless
  `ALLOW_PRIVATE_TARGETS=true`. Cloud metadata addresses such as
  `169.254.169.254` are refused always.
- **Ownership and CORS.** Every load test needs an explicit "I own this target
  or have permission to load-test it" confirmation, and only the origins in
  `CORS_ALLOWED_ORIGINS` may call the API from a browser.

**Why it exists.** A load tester that requests any URL a user types is a
*server-side request forgery* tool. On a public deployment it could scan the
cluster's private network or read the cloud provider's metadata endpoint,
which can hand out credentials. (The Python version's Flask debug mode on
`0.0.0.0` would also expose an interactive debugger that can run code; the
Java backend has none.)

**How it works.**

- `analysis/target/TargetGuard.java` resolves the host name and checks every
  address it resolves to. The analyze, sanity-check and both start endpoints
  call it first and answer 400 `{"error", "blocked_target": true}`.
- Redirects in the reachability probe are followed by hand
  (`GuardedHttpClient`), and every hop is checked *before* it is requested.
- The start endpoints answer 400 `{"missing_confirmation": "i_own_target"}`
  unless the body contains `"i_own_target": true`. The frontend shows a
  checkbox, and the start buttons stay disabled until it is ticked.
- `ALLOW_PRIVATE_TARGETS` defaults to true for local development, so the mock
  target on `127.0.0.1` still works; `k8s/deployment.yaml` sets it to false.
- **One backend process.** The Kubernetes deployment runs **one** replica, and
  the backend serves requests on Tomcat's thread pool:
  - Load tests run on virtual threads of the process that started them, and
    stopping a test needs that same process (`RunRegistry` is in memory).
  - The H2 file database is opened by one process (a queue-mode worker can
    share it only with `H2_AUTO_SERVER=TRUE`).
  - Request threads keep status polls answered while a slow analyze is in
    progress.

**Files.** `analysis/target/TargetGuard.java`, `AddressClassifier.java`,
`Cidr.java`, `analysis/probe/GuardedHttpClient.java`,
`analysis/probe/ReachabilityProbe.java`, `service/TargetAnalysisService.java`,
`service/LoadTestService.java`, `exception/TargetBlockedException.java`,
`exception/OwnershipNotConfirmedException.java`, `config/WebConfig.java`
(CORS), `backend/Dockerfile`, `backend/Dockerfile.worker`, `.dockerignore`,
`k8s/deployment.yaml`, `components/OwnershipCheck.jsx`,
`WebsiteLoadTestSection.jsx`, `App.jsx`, `cli/GateCommand.java` and
`cli/RunCommand.java` (`--i-own-target`),
test `analysis/target/TargetGuardTest.java`.

**Demo.**

- **Blocked address:** analyze `http://169.254.169.254/latest`. The error says
  it is a cloud metadata address.
- **Ownership checkbox:** in step 3, the start button stays disabled until the
  ownership box is ticked.
- **Private targets off:** start the backend with `ALLOW_PRIVATE_TARGETS=false`
  and analyze `http://127.0.0.1:8000/api/login`. It is refused as a loopback
  address.

**Teacher might ask.**
- *Why check every redirect hop yourself?* — An HTTP client that follows
  redirects automatically (`java.net.http` with `Redirect.NORMAL`) has already
  connected to the final URL by the time you see it. Our clients use
  `Redirect.NEVER` and follow redirects by hand, so the guard can say no
  before the request is made.
- *Why one backend process?* — Each process has its own memory. A stop request
  or a status poll could land in a process that never saw the run. One process
  with many threads keeps all runs in one place.

---

## 24. Expected outcomes for edge cases

**What it does.** Every case now carries an expected answer:

- **expects 2xx (accept):** valid input. This covers the original sample,
  which is now the first case (`valid_sample`), and zero for numbers.
- **expects 4xx (reject):** invalid input. This covers missing, null and
  wrong-type fields, empty or 5,000-character strings, negative or huge
  numbers, and SQL injection / XSS strings.

A request is "handled correctly" when the API answered as expected. The
results headline "Handled correctly" instead of the failure rate, and the
mismatches are grouped as:

- **accepted invalid input:** a 2xx for invalid input, i.e. a validation bug;
- **rejected valid input:** a 4xx for valid input;
- **server error:** a 5xx;
- **rate limited:** a 429;
- **connection failure:** no answer at all.

**Why it exists.** Before, every 4xx counted as a failure, so an API that
correctly refused bad input looked broken (a
24.6% "failure rate"). Worse, it
could not spot the opposite problem: an API that *accepts* input it should
refuse.

**How it works.**

- **Generator.** Each generator in `analysis/edgecase/` (one Strategy class
  per kind, combined by `EdgeCaseSuite`) sets `expect` on each case.
- **Confirming cases.** `/api/confirm-selection` validates the field. It also
  accepts per-label overrides (`expectations`), because a field may be
  optional. Custom cases choose their own expectation (default: reject).
- **During the run.** `ResponseClassifier.classify` compares every answer
  with its case's expectation. A match counts as a success; a mismatch is
  recorded as a failure with a message that starts with its category
  (`accepted_invalid_input (200): …`).
- **Results.** `FailureSummarizer` groups by that category, and
  `OutcomeAnalysis.summarize` adds an `outcome_summary` (handled correctly,
  unexpected-answer rate, server-error rate) to the status responses.
- **Older runs.** Runs without expectations keep the old behaviour.
- **Mock target.** It has one deliberate validation bug: a username longer
  than 256 characters is accepted with 200.

**Files.** `analysis/edgecase/*Generator.java`, `EdgeCaseSuite.java`,
`analysis/outcome/ResponseClassifier.java`, `Outcome.java`,
`engine/CaseRecorder.java`, `analysis/outcome/FailureSummarizer.java`,
`analysis/outcome/OutcomeAnalysis.java`, `service/EdgeCaseService.java`,
`model/Expectation.java`, `model/RunCase.java`, the mock's `LoginRules.java`,
`theme.js`, `utils/outcomes.js`, `utils/findings.js`, `utils/report.js`,
`OutcomeStrip.jsx`, `JobResultSummary.jsx`, `App.jsx`,
tests `analysis/outcome/OutcomeClassificationTest.java`,
`analysis/edgecase/EdgeCaseSuiteTest.java`.

**Demo.**

1. Mock target, 15 users, 15 s, all cases.
2. The summary reads "…% of requests got the expected answer" and "3 cases
   were accepted although the input is invalid", including
   `very_long_string_username`, the deliberate bug.
3. In step 3, click "expects 4xx" on `missing_expiresInMins` and
   `null_expiresInMins` (the mock treats that field as optional) and run
   again. Only the real bug is left.

**Teacher might ask.**
- *How does the tool know what is valid?* — From the sample: the sample itself
  must succeed, and each variation is a common way to break input. The
  defaults can be wrong for a particular API, for example an optional field,
  which is why every expectation can be switched before a run.
- *Why are 429 and 5xx never "handled correctly"?* — They are not answers to
  the input. A 429 means the server was overloaded, and a 5xx means it broke.
  Both are reported separately whatever the expectation.

---

## 25. SLO pass/fail verdicts

**What it does.** Before a run you can set *service-level objectives*:
thresholds the run must meet. When the run finishes it gets a big **SLO PASS**
or **SLO FAIL** banner, with one line per threshold showing the target, the
measured value and whether it held. The verdict also appears in the run
history, in the comparison table and in the Markdown report.

**Why it exists.** "Is 4.9%
server errors at 150 users good or bad?" depends on what you promised. An SLO
turns a page of numbers into a yes/no answer, which is what a release decision
(or a CI pipeline, feature 27) needs.

**How it works.**

- **The thresholds.** Five optional thresholds:
  - p95 ≤ X ms;
  - average ≤ X ms;
  - server errors ≤ X %, counting 5xx plus no-answer;
  - unexpected answers ≤ X %, counting accepted invalid input plus rejected
    valid input (feature 24);
  - throughput ≥ X req/s.
- **Validation and storage.** `analysis/slo/SloValidator.java` validates the
  ranges, and both start endpoints accept `slo`. The thresholds are stored
  with the run (`model/SloSettings.java`, an embedded JPA value; Hibernate's
  `ddl-auto: update` adds the columns to older databases), and also with each
  child of a split run and each repeat of a series.
- **The verdict.** When a run is `completed`, the status response gains
  `slo_verdict` and `/api/jobs` gains a one-word `slo_verdict` per run.
- **Unmeasurable thresholds.** One that cannot be measured is shown as "not
  measured" and does not fail the run, for example the unexpected-answer rate
  of a website run.

**Files.** `analysis/slo/Slo.java`, `SloThreshold.java`, `SloValidator.java`,
`SloEvaluator.java`, `SloVerdict.java`, `model/SloSettings.java`,
`service/RunAnalyzer.java`, `service/RunViewService.java`,
`components/SloConfig.jsx`, `components/SloVerdict.jsx`, `JobResultSummary.jsx`,
`CompareView.jsx`, `SeriesSummary.jsx`, `App.jsx` (history column),
`utils/report.js`, test `analysis/slo/SloTest.java`.

**Demo.** Keep the defaults (p95 ≤ 500 ms, server errors ≤ 1 %) and run the mock
target with all cases at 15 users for 15 s. The result is **SLO FAIL**: p95 is
fine (p95 147.97 ms), but the
two cases that crash the mock make about
11.11% server
errors. Untick `wrong_type_expiresInMins` and `very_large_expiresInMins` and run
again for **SLO PASS**. The history list shows both verdicts.

**Teacher might ask.**
- *Why do 429s not fail the SLO?* — Rate limiting is the server protecting
  itself and answering quickly; it is reported (problem rate, outcome strip) but
  the SLO's error threshold is about the server *failing*: 5xx and no answer.
- *Why is p95 the default and not the average?* — The average hides the slow
  tail; p95 tells you what the slowest one-in-twenty users experience, which is
  what people notice.

---

## 26. Capacity finder ("Find capacity")

**What it does.** A second run mode. Instead of one fixed number of users, the
load climbs in steps: start users, then more every *N* seconds, up to a
maximum. Each step is judged against the SLO on its own. The answer is a
headline such as **"Max sustainable: 120
users at p95 < 500 ms, server errors < 1%"**, plus the **breaking point**: the
first step that failed, and why.

The result has two charts that share the step axis, both with the SLO line
dashed and the breaking point marked:

- p95 per step;
- error rates per step.

A steps table and a report section complete it. With "Stop after the first
step that misses the SLO" ticked, the run ends right after the breaking step.

**Why it exists.** "Did 150 users work?" is a yes/no for one load level. The
question people actually ask is "how many users can it take?". Answering it
by hand means guessing, running, and guessing again.

**How it works.**

- **The step shape.** `engine/LoadProfile.Steps` tells the engine how many
  users to run at each moment of the run. Fixed runs use `LoadProfile.Fixed`,
  so they are untouched.
- **Step records.** `engine/StepTracker` receives every one-second interval
  from the sampler. At the end of every step it records that step's users,
  requests, average, p95 (from the step's own histogram) and failures by
  category. With early stop on, it ends the run right at the step boundary if
  the step missed the SLO.
- **After the run.** `analysis/capacity/CapacityEvaluator.java` judges each
  step and finds the highest step that passed (with every lower step passing
  too) and the first step that failed.
- **Throughput is not judged per step.** Small steps are slow on purpose, so
  a minimum requests-per-second threshold would fail them.
- **Partial steps.** A step that ran for less than ¾ of its time is shown
  hollow and ignored.
- **Single job.** A capacity run is always one job, never split. The steps and
  their judgement need one engine instance, and the UI says so.
- **Limits.** Users 1–1000, spawn rate ≤ 20/s, total ≤ 300 s, and steps of at
  least 5 s. The spawn rate must be fast enough to reach each step's users in
  half a step (`CapacityPlanValidator`).

**The mock target.** It has 8 workers (a fixed pool of 8 threads). Each
request holds one for 50–150 ms, so it can serve about 80 requests per second;
beyond that, requests queue and p95 climbs. That is the breaking point the
demo finds.

**Files.** `engine/LoadProfile.java`, `engine/StepTracker.java`,
`analysis/capacity/CapacityPlanValidator.java`, `CapacityEvaluator.java`,
`CapacityStep.java`, `CapacityResult.java`, `StepRecord.java`,
`service/LoadTestService.java` (`mode: "capacity"`),
`service/EngineSpecFactory.java`, `model/CapacityPlan.java`,
`model/StepRecordsConverter.java`, the mock's `MockConfig.java`
(`MOCK_WORKERS`), `components/CapacityConfig.jsx`,
`components/CapacityResult.jsx`, `LoadTestConfig.jsx`, `JobResultSummary.jsx`,
`App.jsx`, `utils/report.js`, tests `analysis/capacity/CapacityTest.java`,
`engine/LoadEngineTest.java` (`stepProfileRecordsStepsAndStopsEarly`),
`it/ApiAgainstMockIT.java` (`capacityRunStopsAtTheFirstFailingStep`).

**Demo (about 1½–2 minutes).**

1. Start the mock target.
2. Analyze `http://127.0.0.1:8000/api/login` with the demo sample and generate
   cases.
3. In step 3, click **Select none** and tick only `valid_sample`. All cases
   would include the two that crash the mock, and their
   11.11% server errors fail
   the SLO at every step.
4. Click **Find capacity** and keep the defaults:
   - start 20 users, add 20 per step;
   - 12 s per step, up to 200 users;
   - spawn rate 20/s, early stop on;
   - SLO p95 ≤ 500 ms, server errors ≤ 1%.
5. Tick the ownership box and start. In our run it stopped after about
   84 s (7 steps of 12 s):
   **max sustainable 120 users**,
   breaking point **140 users**
   (p95 691.71 ms). The throughput
   levelled off at about
   79 req/s,
   the mock's 8 workers.

**Teacher might ask.**
- *Why judge each step separately instead of the whole run?* — The last steps
  are meant to overload the target, so the whole run would always fail. The
  useful answer is the boundary: the last load that held and the first that
  did not.
- *How accurate is the breaking point?* — It is as fine as the step size: with
  20-user steps, the true limit lies somewhere between
  120
  and 140
  users. Use smaller steps near the boundary for more precision.

---

## 27. CLI and a CI performance gate

**What it does.** The backend jar has a command-line mode (`cli/RunCommand.java`,
picocli) that runs one load test from a YAML config file, with any value
overridable by a flag:

```
java -jar backend/target/load-tester.jar run --config loadtest.example.yml
java -jar backend/target/load-tester.jar run --config loadtest.example.yml --users 30 --duration 20
java -jar backend/target/load-tester.jar run --config loadtest.example.yml --mode capacity --cases valid_sample
```

It prints a readable summary and the SLO verdict, and writes a Markdown report,
the full JSON result and a short `summary.md`. It exits **0** when the SLO
passed, **1** when it failed and **2** on any error.

A new GitHub Actions pipeline (`.github/workflows/ci.yml`) runs four jobs on
every push and pull request to `main`:

- **backend tests:** `./mvnw verify` (JUnit 5 and Mockito unit and MockMvc
  tests, integration tests against the Java mock, the JaCoCo coverage gate);
- **frontend:** lint and build;
- **performance gate:** builds the jars, starts the mock target and the
  backend, runs the CLI with a short strict config, uploads the report as an
  artifact and writes the verdict table into the job summary;
- **images:** on pushes to `main` only, after the other jobs pass, builds the
  backend, mock-target and frontend images and pushes them to
  `ghcr.io/xan-antx/elevate-backend`, `elevate-mock` and `elevate-frontend`
  with the commit SHA and `latest`.

**Why it exists.** A load test that someone has to remember to run in a
browser is not a safety net. The gate makes "it got slower" or "it now crashes
on input X" fail the build, the same way a failing unit test does.

**How it works.**

- **Only the HTTP API.** The CLI talks to the backend exactly like the
  frontend: health, analyze, generate and confirm cases (with `include`,
  `exclude` and per-case `expect` from the config), start, then poll every 2 s.
- **Exit codes.** Capacity mode exits 0 when the target sustained at least
  `capacity.min_users`, which defaults to `start_users`. A crash inside the
  CLI also exits 2, so it is never mistaken for "SLO failed".
- **Header values stay out.** Request header values are never written to the
  output files.
- **The gate config.** `loadtest.ci.yml` sends 10 users for 25 s, under the
  mock's rate limit, with strict thresholds: p95 ≤ 500 ms, server errors ≤ 1%,
  unexpected answers ≤ 1%. It excludes the mock's three *deliberate* bugs,
  because the gate is there to catch **new** ones.
- **Older pipeline.** `.gitlab-ci.yml` is kept, but GitHub Actions is now
  the primary pipeline.
- **Kubernetes image.** The deployment uses the GHCR image.

**Files.** `cli/LoadTestCli.java`, `cli/RunCommand.java`, `cli/CliRunner.java`,
`cli/CliConfig.java`, `cli/BackendClient.java`, `cli/CliOutputs.java`,
`cli/VerdictTable.java`, `loadtest.example.yml`, `loadtest.ci.yml`,
`.github/workflows/ci.yml`, `k8s/deployment.yaml`, `backend/pom.xml`
(picocli, Jackson YAML), `frontend/eslint.config.js` (lint now passes, so CI
can run it), tests `cli/CliTest.java`, `it/ApiAgainstMockIT.java`
(`cliPassesTheCiGateAndFailsOnTheBugs`).

**Demo.** With the backend and mock target running, in the repository root:

| Command | Result |
|---|---|
| `java -jar backend/target/load-tester.jar run --config loadtest.ci.yml` | **PASS**, exit 0 (100% handled correctly, p95 ≈ 149.5 ms) |
| `java -jar backend/target/load-tester.jar run --config loadtest.example.yml --duration 15` | **FAIL**, exit 1 (the mock's crash bugs: server errors ≈ 6.55%) |
| `java -jar backend/target/load-tester.jar run --config loadtest.example.yml --mode capacity --cases valid_sample` | "Max sustainable: 120 users…", exit 0 |

Check the exit code with `echo $?` (bash) or `$LASTEXITCODE` (PowerShell).

**Teacher might ask.**
- *Why exclude the known bugs from the gate instead of fixing the thresholds
  around them?* — The deliberate bugs make a fixed share of requests fail, and
  that share varies from run to run. Loose thresholds would hide real
  regressions, while strict ones would fail randomly. Excluding known,
  documented issues ("quarantining" them) keeps the gate strict and stable.
- *Why does the gate run the real backend and not a mock of it?* — It checks
  the whole path: API, load engine, result merging, verdict. Only the
  *target* is a mock, so the numbers are repeatable.

---

## 28. One-command demo (docker compose)

**What it does.** `docker compose up --build` at the repository root starts
the whole system in containers. http://localhost:8080 then opens the app
already filled in for the bundled mock target.

**Why it exists.** Before, a demo needed three terminals (backend, frontend,
mock target), a Java 21 JDK and Node. Now it needs Docker.

**How it works.**

- **Three services** (`docker-compose.yml`):
  - `backend`: the production image, the backend jar on a Java 21 JRE as a
    non-root user, H2 in a named volume;
  - `mock`: its own image (`mock-target/Dockerfile`) running the Java mock
    target on `0.0.0.0`;
  - `frontend`: a two-stage build. Node builds the React app, then nginx
    serves it and proxies `/api` to the backend, so the browser talks to one
    origin and CORS stays strict.
- **Pre-filled app.** Build arguments set `VITE_API_BASE` and pre-fill the
  target `http://mock:8000/api/login` and the demo sample.
- **Private targets.** Inside the compose network the mock has a private
  address, so this file (and only this one) sets `ALLOW_PRIVATE_TARGETS=true`.

**Files.** `docker-compose.yml`, `backend/Dockerfile`, `mock-target/Dockerfile`,
`frontend/Dockerfile`, `frontend/nginx.conf`, `frontend/.dockerignore`,
`.dockerignore`, the mock's `MockConfig.java` (`MOCK_HOST`, `MOCK_PORT`),
`App.jsx` (optional `VITE_DEFAULT_TARGET` / `VITE_DEFAULT_SAMPLE`),
`RUNBOOK.md`.

**Demo.**

1. Run `docker compose up --build` and wait for "Started" on all three
   services.
2. Open http://localhost:8080 and click Analyze, then Generate edge cases.
3. Tick the ownership box and start.
4. To use the CLI from the host as well:
   `java -jar backend/target/load-tester.jar run --config loadtest.ci.yml --api http://localhost:8080 --url http://mock:8000/api/login`
   (nginx forwards `/api` to the backend).

**Teacher might ask.**
- *Why does nginx proxy the API instead of the browser calling the backend
  directly?* — Same origin: the page and the API are both on :8080, so no CORS
  exceptions are needed and only one port has to be exposed.
- *Why is ALLOW_PRIVATE_TARGETS on here but off in Kubernetes?* — In compose
  the only private address in reach is our own mock target. On a public
  server, private addresses are the cluster's internal network, which is
  exactly what must never be probed.

---

## 29. Robustness: one run at a time, honest failures, safe probing

**What it does.** Five fixes found in a code review:

- **One test at a time, enforced by the server.** Every client (another browser
  tab, the CLI, a script) gets **429** "A load test is already running…" while
  `MAX_ACTIVE_RUNS` runs are active. The default is 1, and the UI shows it as a
  "Server busy" notice next to the start button. A split run, a capacity run
  or a repeat series counts as one run.
- **Failed means failed.** If the load engine or the run setup raises an
  error, the run is `failed` and the result shows the error message (with any
  header values redacted). In the Python version a crashed Locust used to be
  reported as `completed`, with empty results.
- **Interrupted runs.** Runs left `queued` or `running` when the backend
  restarts are marked `interrupted` at startup. They no longer spin forever in
  the UI or block the one-at-a-time limit.
- **Split-run status stays current.** A split run's stored status follows its
  children whenever one changes. The history never shows a finished split run
  as "queued".
- **Read-only probe.** Analyze only sends HEAD, then GET, then OPTIONS. It used
  to fall back to `POST {}`, which a real API might act on: creating a record,
  for example. A server that refuses HEAD and GET (404/405) still counts as
  reachable, with "method not allowed".

**Why it exists.** These checks lived only in the browser or relied on luck:

- two browser tabs could start two tests at once;
- a crashed load process looked like a successful run with no data;
- a backend restart left runs "running" forever;
- the probe could change data on the server being tested.

**How it works.**

- **The run limit.** The start endpoints take a permit from
  `ActiveRunLimiter`, a `Semaphore` with one permit per allowed run, before
  they create the new run. `tryAcquire` checks and takes in one atomic step,
  so two simultaneous requests cannot both get through. The permit is given
  back when the run ends, however it ends.
- **Engine failures.** Request failures are measurements: the engine records
  them and the run still completes. Only an exception in the engine itself,
  or while storing its results, makes `JobRunner` mark the run `failed`.
- **The startup sweep.** `StaleRunRecovery` calls
  `RunStore.markLeftoversInterrupted()` when the app starts, before the web
  server accepts requests. It is skipped in queue mode, where a remote worker
  might still be running jobs, and with `RECOVER_STALE_RUNS=false`. On a
  normal shutdown the backend also stops its running engines and stores
  their partial results as `interrupted`.
- **Telling an endpoint from a page.** Type detection
  (`analysis/probe/TypeDetector.java`) now uses every probe answer:
  - a JSON reply anywhere means an API;
  - an `Allow` header that offers POST but not GET means an endpoint;
  - a 405 for GET points the same way;
  - only a real page returned by GET counts as a website.

**Files.** `controller/LoadTestController.java`, `service/ActiveRunLimiter.java`,
`exception/TooManyActiveRunsException.java`, `service/LoadTestService.java`,
`service/JobRunner.java`, `service/RunExecutor.java`,
`service/StaleRunRecovery.java`, `service/RunStore.java`,
`model/SplitGroup.java` (`deriveStatus`),
`analysis/probe/ReachabilityProbe.java`, `analysis/probe/TypeDetector.java`,
`App.jsx`, `JobResultSummary.jsx`, `AnalyzeSummary.jsx`, `cli/RunCommand.java`,
`cli/GateCommand.java`, tests `analysis/probe/ProbeTest.java`,
`service/LoadTestServiceTest.java`, `service/RunControlTest.java`,
`it/ApiAgainstMockIT.java` (`leftoverRunsAreMarkedInterruptedOnStartup`).

**Demo.**

1. Start a 30 s run in the UI, then run
   `java -jar backend/target/load-tester.jar run --config loadtest.ci.yml` from
   the repository root. The CLI stops with "returned 429: A load test is
   already running…", exit code 2.
2. Start a run in a second browser tab: "Server busy: …".
3. Restart the backend during a run: the run becomes **Interrupted** and the
   start button works again.
4. Analyze the mock target: "The server answered, but refused HEAD and GET
   (405); it allows OPTIONS, POST", and it is still classified as an **api**.

**Teacher might ask.**
- *Why does the limit need a semaphore?* — Without an atomic check-and-take,
  two requests arriving at the same moment could both count "0 active runs"
  before either has created its run. `Semaphore.tryAcquire` makes
  check-then-take one step.
- *How does the backend tell a crash from failed requests?* — Failed requests
  are normal in a load test, so the engine counts them as results. A run is
  `failed` only when the engine itself, or storing its results, throws an
  exception.

---

## Job states

Every run (a single job, a split group, or a repeat series) moves through
these states. The last four are **terminal**: the run never changes again, and
the browser stops polling.

```
queued ──► running ──► completed
   │          ├──────► failed       (the load engine or the run setup raised an error)
   │          ├──────► cancelled    (Stop test; partial results kept if any)
   └──────────┴──────► interrupted  (the backend stopped or restarted while the run was in progress)
```

- A run is `completed` only when the engine finished without an error **and**
  its results were stored. Otherwise it is `failed`, and the error shows the
  error message.
- The Java backend never produces the `timeout` state of the Python version
  (Locust running longer than duration + 60 s): the engine stops itself at the
  end of the duration. The UI still handles `timeout` from the Python backend.
- When the backend starts, every job, group and series still `queued` or
  `running` belonged to the previous process. That process is gone, so it can
  never finish, and the run is marked `interrupted`.

- A **split group** is `cancelled` once every child has stopped and at least
  one was cancelled; `failed` and `interrupted` in any child take priority,
  and it is `running` while any child still runs.
- A **series** is `completed` only if every repeat completed; a repeat that
  failed makes it `failed`; Stop makes it `cancelled`.
- Only `completed` runs can become the baseline.
- A **capacity run** uses the same states. It may end before its planned time
  when early stop finds a failing step, and it is still `completed`.

---

## Five-minute demo script

A complete walkthrough, timed for about five minutes. The numbers in brackets
are what our runs produced (see [Evaluation](evaluation.md)); yours will be
close.

**Before you start (not part of the five minutes).**

- Run `docker compose up --build` in the repository root, wait until all
  three services are up, and open http://localhost:8080.
- *Without Docker:* build with `./mvnw package -DskipTests`, then start
  `java -jar mock-target/target/mock-target-1.0.0-exec.jar`,
  `java -jar backend/target/load-tester.jar` and `npm run dev` (in
  `frontend`). Open http://localhost:5173 and type the target
  `http://127.0.0.1:8000/api/login` and the sample
  `{"username": "demo", "password": "demo123", "expiresInMins": 30}`
  yourself.

**0:00 — Target and safety (30 s).**

1. The target is already filled in. Click **Analyze**; it is classified as an
   **api**.
2. Show the guardrail: change the URL to `http://169.254.169.254/latest` and
   click Analyze. It is refused as *a cloud metadata address*.
3. Put the mock URL back and Analyze again.

**0:30 — Edge cases with expected outcomes (45 s).**

1. Click **Generate edge cases**. There are 27 cases, and each shows
   "expects 4xx" or "expects 2xx". The first one, `valid_sample`, is the
   sample itself.
2. Point out the two `expiresInMins` cases under Missing field and Null value.
   The mock treats that field as optional, so click their **expects 4xx**
   buttons to switch them to **expects 2xx**.

**1:15 — A fixed run with an SLO (60 s).**

1. Keep **Fixed load** and set 15 users, spawn rate 15 and 20 s.
2. The **SLO** box is on by default: p95 ≤ 500 ms and server errors ≤ 1%.
3. Tick **"I own this target or have permission to load-test it"**; the start
   button is disabled until you do. Then start.
4. The page scrolls to the run. Mention **Stop test**, which ends a run early
   and keeps partial results.

**2:15 — Read the result (45 s).**

- **The banner:** **SLO FAIL**. p95 is fine
  (p95 147.71 ms),
  but server errors are
  7.52%:
  two cases crash the mock.
- **Summary of results:**
  "84.51%
  of requests got the expected answer"; "1 case was accepted although the
  input is invalid", which is `very_long_string_username`, the deliberate
  validation bug; and "2 cases caused server errors (500)".
- **Why did these fail?** Open it: one purple card (accepted invalid input)
  and one red card (500).
- Click **Download report (.md)**. It contains the SLO table and the
  expected-vs-actual section.

**3:00 — Find capacity (90 s, the run takes about 84 s (7 steps of 12 s)).**

1. Back in step 3, click **Select none** and tick only `valid_sample`.
2. Click **Find capacity** and keep the defaults: 20 users, +20 every 12 s,
   up to 200, spawn rate 20, and stop after the first failing step.
3. Start. While it runs, explain the idea: the load climbs step by step, each
   step is judged against the SLO, and the run stops at the first failure.
4. Read the headline: **"Max sustainable: 120
   users at p95 < 500 ms…"**, with the breaking point at
   **140 users**
   (p95 ≈ 691.71 ms).
5. In the charts, p95 jumps once the mock's 8 workers are saturated (about
   79 req/s),
   and the breaking point is marked.

**4:30 — Compare and CI (30 s).**

1. In **Compare runs**, tick the fixed run and an earlier one. The table now
   has an **SLO verdict** row, and the history list has an **SLO** column.
2. Close with the pipeline. In the repository root, run
   `java -jar backend/target/load-tester.jar run --config loadtest.ci.yml --api http://localhost:8080 --url http://mock:8000/api/login`.
   With the app from source, use plain
   `java -jar backend/target/load-tester.jar run --config loadtest.ci.yml`
   instead.
3. It prints **SLO verdict: PASS** and exits 0. The same command runs as the
   performance gate in GitHub Actions on every push, where an SLO miss fails
   the build.
