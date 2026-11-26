# UI revamp plan (ui-revamp branch)

Source: `/impeccable critique` (dual-agent) of the current frontend. It was
rated **Acceptable** (the score was taken in the Python repository and is not repeated here),
with three P1 issues. The snapshot is in
`.impeccable/critique/`. Product truth is in `PRODUCT.md` and the visual
system in `DESIGN.md`. The earlier review is `docs/ux-review.md`.

The plan was made and carried out in the Python repository; the revamped
frontend was ported to this edition file for file, with the Java adaptations
listed in `PROGRESS.md`.

## What the critique found (short)

1. **Results tell the truth in the wrong order.**
   - The SLO banner renders above the findings.
   - The headline finding is always green.
   - `slice(0, 4)` silently drops findings, for example rate limiting on a run
     where a large share
     of requests were 429s.
   - Cases that got 0 requests disappear from the stats (Locust leaves them
     out; the Java backend lists them with 0 requests), so a run can look
     clean while several
     cases were never sent.
2. **One word, three numbers.**
   - "Unexpected" in the table is one count
     and includes 5xx.
   - "Unexpected answers" in the SLO is a different count.
   - Compare shows "Failure rate %", and there is also a separate "Problem rate".
3. **Nothing is above the fold after Start.** The progress bar sits at
   the bottom of the page in a
   900 px viewport, and the results land below it.
4. **Errors appear far from their cause.** There is one banner at the top of
   the page, and focus jumps there.
5. **Step 3 is overloaded.** It has more than 40 controls and a 380 px
   scroll well. The descriptions repeat ("SQL injection attempt in
   'username'" ×3), and the payloads are never shown.
6. **The detector found:**
   - mono labels in about many
     places (a Mono Means Measured violation);
   - side-tab borders on the SLO and capacity banners;
   - nested bordered boxes;
   - 01–05 numbered labels;
   - paragraphs of over 100 characters;
   - all buttons defaulting to `type=submit`;
   - case checkboxes named "on".

## Information architecture

There are three views, switched by a tiny hash router (`#/new`, `#/run`,
`#/history`). It needs no dependency, and the browser Back button works.

| View | Contents |
|---|---|
| **New test** | **Main column:** target URL, then the analyze chip (reachable, status, API/website, with a "?" popover holding the long explanation), then ownership, then the sample JSON with inline validation, then the case table. **Sticky side column:** preset, mode (Fixed load / Find capacity), load numbers, request estimate, SLO, and **Start**, which is always visible. |
| **Run** | Opens at the top of the viewport. Live progress first, then the results: the findings headline, coverage, KPIs, the outcome bar, the SLO card, the case table, "Why did these fail?" and charts, then actions (Mark as baseline, Download report, Re-run with same settings, Edit and re-run). |
| **History** | The run list: relative time, full target path, users, mode, status, verdict, findings count. Empty columns are hidden. Baselines are managed here. Compare 2–4 runs with an explicit "reference run" marker. |

A **run pill** in the header shows a running test (elapsed / about total,
plus Stop) from any view. The health light stays subtle while the backend is
connected. When it is offline, a full-width banner appears under the header.

## One "unexpected" concept

A request's answer is **unexpected** when it differs from what a correct API
gives: valid input should get 2xx, invalid input a 4xx rejection, and a 429,
a 5xx or no answer never counts as correct. So
**unexpected = total − handled correctly**. It is broken down by outcome kind
(accepted invalid input, rejected valid input, server error, rate limited,
connection, other).

That one number drives:
- the KPI ("Unexpected answers", shown as a percentage with the count and the total);
- the outcome bar ("Expected answer" plus the kinds);
- the case table's "Unexpected" column;
- the chart's "unexpected/s" line;
- Compare and the report.

"Problem rate" and "Failure rate" go away. For legacy runs without
expectations, unexpected = the failure count in the stats (every non-2xx
answer, Locust's rule) and the label says so.

**Bugs** are cases whose unexpected answers show a defect: accepted invalid
input (a validation bug), rejected valid input, and server errors. Rate
limiting and connection failures are load effects, reported separately.

**Coverage:** the cases sent (`case_expectations` keys) are compared with the
stats rows that have requests. A case with no row, or 0 requests, is
**untested**, and it is shown in its own grey state and never as a green 0%.

## Components and modules

```
src/
  router.js               hash router (useView)
  api.js                  BASE, fetchJson, error text
  hooks/useHealth.js      polling, offline state
  hooks/useTarget.js      url, analyze, ownership
  hooks/useCases.js       sample, generate, select, expect, custom cases, filters
  hooks/useLoadSettings.js presets, fixed/capacity, SLO, estimate
  hooks/useRun.js         start, poll, stop, result, regression info, run config store
  hooks/useHistory.js     list, baseline, compare
  utils/runReport.js      the one derivation of findings, coverage, unexpected and bugs
  utils/estimate.js       request estimate and untested-case risk
  utils/runStore.js       localStorage of run configs (payloads, full URL, settings)
  views/NewTestView.jsx, views/RunView.jsx, views/HistoryView.jsx
  components/…            small pieces (FindingsHeadline, CoverageLine, KpiRow,
                          SloCard, CaseResultsTable, FailureReasons, Timeline,
                          CaseTable, LoadPanel, SloFields, RunPill, Popover, …)
```

## Order of work (priority order, demo first)

The app must work after every step, so each step ends with lint and build
passing, a real run against the mock, and a commit.

1. **Results.**
   - Findings headline first (cases and payloads named), then coverage and
     untested cases, then the SLO as a secondary card.
   - One unexpected metric throughout.
   - Charts get a data-scaled y-axis, SLO threshold lines and a ramp-up band.
   - Failures sit next to their case and payload.
   - New actions: Re-run and Edit and re-run.
2. **Visual cleanup.**
   - Mono only for data, everywhere.
   - Remove the side-tab borders and nested boxes.
   - Weight before size; projector-readable captions.
   - Inline errors next to their controls.
   - Health light subtle, offline banner prominent.
3. **New test view.**
   - Three-view IA.
   - Compact setup with a sticky Start.
   - Case table with chips, search, payloads and a segmented expected-answer control.
   - Presets and a live estimate with a zero-request warning.
   - SLO fields with an explicit "off" state.
4. **History view.** The run list, baselines, and Compare with a reference marker.
5. **Final checks.**
   - break-ui with worst-case data;
   - accessibility and UX heuristics review;
   - `/impeccable audit` and polish;
   - the docs: DESIGN.md, the design.json sidecar and docs/ux-review.md.

## Backend changes recommended but NOT made

1. **Coverage guarantee.** Done in the Java edition. The Python engine
   (`dynamic_locustfile.py`) picks `random.choice(CASES)` per request (with a
   0.5–2 s wait), so no case is guaranteed a request. The Java engine
   (`engine/VirtualUser.java`) iterates each user over the cases in order,
   starting at a random case, so one user sends every case once it has sent
   as many requests as there are cases.
2. **Store the target path.** `target_url` holds only the host. The Java
   backend stores the path separately (`TestRun.targetPath`) because its
   engine needs it, but the API does not return it. History therefore cannot
   show the full target for runs started elsewhere (the CLI, other browsers).
   The frontend keeps the full URL locally for runs it started.
3. **Store the case payloads per run.** These are needed to show the
   triggering payload and to re-run from History for runs not started in this
   browser. The Java backend stores them (`RunCase`), but the API does not
   return them.
4. **Findings and coverage counts in `/api/jobs`.** Today History would need
   one status request per run to show a findings count.

## Decisions made overnight

- **The plan was self-approved** (you asked for unattended work), and the
  questions at the end of the critique were skipped for the same reason.
- **Dev environment.** In the Python repository that night, :5000 was the
  Docker backend, whose CORS allows only :8080, and :8000 was this Java
  edition's mock (`load-tester-java-mock-1`). So two launch configs were added
  there, on :5001 and :8001. In this repository `.claude/launch.json` was
  rewritten:
  - `backend-dev`: the Spring Boot backend from source on **:8080**;
  - `mock-dev`: the Java mock target on **:8000**;
  - `frontend`: Vite on :5173.

  `frontend/.env.development` points Vite at :8080.
- **Run configs are kept in `localStorage`** (cases with payloads, the full
  URL, settings), keyed by run id, capped at 50 entries and wrapped in
  try/catch. This powers payloads in findings, Re-run and the full target
  path, without a backend change. Runs started outside this browser show case
  names only.
- **Router:** a hash router of about
  29 lines
  instead of react-router, so there is no new dependency.
- **Presets:**
  - Smoke: 5 users, 5/s, 20 s.
  - Normal: 25 users, 5/s, 60 s.
  - Stress: 150 users, 20/s, 60 s.
  - Custom keeps whatever the user typed.
- **Estimate model:** each virtual user sends about 1 request per
  (1.25 s mean wait + average response time); ramp-up counts as
  users / spawn rate. Expected untested cases ≈ N·e^(−requests/N). The UI
  warns when that is 0.5 or more. The model assumes each request picks a
  random case, as the Python engine does; the Java engine cycles through the
  cases, so it leaves fewer cases untested than the estimate predicts.
