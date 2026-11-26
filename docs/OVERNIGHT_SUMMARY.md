# Overnight summary: UI revamp (ui-revamp branch)

> **Note.** This UI revamp was made in the Python repository (branch
> `ui-revamp`) and ported to the Java edition unchanged, except for the Java
> adaptations listed in `PROGRESS.md` ("Frontend changes"). Commands, ports and
> backend details below are given for the Java edition.

Everything is committed on `ui-revamp`. `main` was not touched. The backend
and its API contracts are unchanged.

| Check | Result |
|---|---|
| `npm run lint` | clean |
| `npm run build` | clean |
| Backend tests (`./mvnw verify`) | 254 passed |

## Commits, in order

1. **Results: findings first, coverage of untested cases, one "unexpected"
   metric.** Includes the plan in `docs/ux-revamp-plan.md`.
2. **Visual cleanup.** Mono for data only, a quiet health light, and an
   offline banner.
3. **Three views: New test, Run, History.** Covers the History and Compare
   work too.
4. **Final checks.** break-ui and accessibility fixes.
5. **Docs.** DESIGN.md, `.impeccable/design.json`, `docs/ux-review.md` and
   this file.

## What changed

- **Results (the demo's centrepiece).**
  - The page opens with "N cases found bugs". Each bug kind lists its cases,
    "expected 4xx, got 200" and the exact payload that triggered it.
  - Then coverage: "27 of
    27 cases tested; 0
    got no requests". Untested cases are dashed, never a green 0%.
  - Then rate limiting, labelled as a load effect rather than a bug.
  - The SLO is a smaller card underneath.
  - A run with bugs or untested cases never looks all-green.
- **One number for quality: "Unexpected answers".** It is used the same way
  in the KPI, the outcome bar, the table, the chart, Compare and the
  downloadable report.
- **Charts.**
  - The y-axis fits the data.
  - The p95 SLO target is drawn as a dashed line.
  - Ramp-up is shaded.
- **Three views.**
  - **New test:** compact setup, with Start always visible on the right.
  - **Run:** progress, then results, at the top of the screen. A "Running"
    pill follows you to other views.
  - **History:** relative times, full URLs, findings count, verdict,
    baselines, and Compare with a "Reference" marker and a one-line "what
    changed" per run.
- **Case table.**
  - Category chips with counts, search, and select all or none per filter.
  - The value each case sends.
  - An obvious 2xx / 4xx control.
- **Load.**
  - Smoke / Normal / Stress / Custom presets.
  - A live request estimate that warns when cases may never be sent (it
    predicted 2
    of 27 untested, and the run had
    0).
  - SLO targets with on/off switches.
- **Actions:** Re-run with same settings, Edit and re-run, Mark as baseline,
  and Download report.
- **Errors and connection.** Errors appear under the field that caused them.
  The "Connected" light is quiet; when the backend is offline, a red banner
  with "Try again" appears.
- **Code.**
  - The 1352-line `App.jsx` is now
    about 174 lines.
  - Views live in `views/` and hooks in `hooks/`.
  - All result figures come from `utils/runReport.js`.
  - No new dependencies.

## How to run it

### Option A: from source

This is how everything was tested that night (in the Python repository, on
other ports; see `docs/ux-revamp-plan.md`).

- In this repository the dev setup uses the backend on **:8080** and the mock
  target on **:8000**. Both are in `.claude/launch.json` as `backend-dev` and
  `mock-dev`, next to `frontend`.
- `frontend/.env.development` points Vite at :8080, and the target
  `http://127.0.0.1:8000/api/login` is the default.

Build and start the backend:

```powershell
./mvnw -q package -DskipTests; java -jar backend/target/load-tester.jar
```

Start the mock target:

```powershell
java -jar mock-target/target/mock-target-1.0.0-exec.jar
```

Start the frontend:

```powershell
cd frontend; npm run dev
```

Then open http://localhost:5173. The target `http://127.0.0.1:8000/api/login`
is pre-filled.

### Option B: Docker

Running `docker compose up --build` rebuilds the frontend image with the
revamp.

- The Python edition's containers, if they are running on the same machine,
  hold :8080 and :8000, so our frontend and mock containers cannot start.
- Stop them yourself first, if that is fine. They are not part of this
  project.

```powershell
docker ps --format "{{.Names}}  {{.Ports}}"
```

Then `docker stop` the containers that hold :8080 and :8000 (their names depend on the Python checkout's folder name).

```powershell
docker compose up --build
```

Then open http://localhost:8080. The target `http://mock:8000/api/login` is
pre-filled.

## Five-step demo script (about 4 minutes)

1. **Set up.** On **New test**, press **Analyze**. The chips show reachable,
   status 405 (POST only) and "an API"; "?" explains what was checked. Tick
   "I own this target". Press **Generate cases**: 27 cases appear, each
   showing what it sends (for example `username: "' OR 1=1 --"`).
2. **Tell it what is correct.** `expiresInMins` is optional, so set
   `missing_expiresInMins`, `null_expiresInMins` and `zero_expiresInMins` to
   **2xx**. Pick **Normal** (25 users, 60 s) and point at the estimate:
   "about 39
   requests per case". Press **Start load test**.
3. **The verdict.** The Run view opens at the top, and the pill shows progress
   from any view. The result leads with **"3 cases found bugs"**:
   - `very_long_string_username` was accepted with 200, a validation bug,
     shown with its payload;
   - `wrong_type_expiresInMins` and `very_large_expiresInMins` crashed the
     server with 500.

   Coverage: "all 27 cases got requests". The SLO card sits below as the
   secondary check.
4. **Under load.** Press **Mark as baseline**, then **Edit and re-run**, pick
   **Stress** (150 users) and Start. The result now also shows **rate limiting
   (429)** as a load effect, "not a bug". The run was split across 3 engine
   instances; the Java backend merges their histograms exactly and sends
   `p95_exact: true`, so the p95 says "exact (merged histograms)" with no ≈
   (against the Python backend the same UI marks it ≈). "Compared with the
   baseline" lists the cases that got worse.
5. **History and Compare.** Open **History**, tick the Normal run (it becomes
   the **Reference**) and the Stress run, and press **Compare**: "unexpected
   answers rose from …% to …%; p95 rose from … to … ms; …% rate limited".
   Finish with **Download report (.md)**.

**Tip:** to show the coverage warning, choose **Smoke** before step 2. The
estimate warns "About 2
of 27 cases may never be sent", and the result marks any case that got no
request as untested. (The estimate assumes random case choice; the Java
engine cycles through the cases, so the result may show fewer untested cases
than predicted.)

## What's left

- **Not checked on a real projector.** Small 12 px text remains in captions,
  legends and counts. Headlines and figures are 28 px.
- **No assistive-technology pass.** I did not run a full keyboard-only or
  NVDA pass, only a structural review: labels, focus ring, `aria-pressed`,
  `aria-describedby`, and Esc on popovers. Segmented controls use Tab, not
  arrow keys.
- **History's findings column** loads up to 25 runs' details on first open.
- **Re-run and payloads** only work for runs started in this browser; runs
  started from the CLI or another browser show case names only.

## Anything broken

- **Nothing known in the app.**
- **The Docker demo stack conflicts with the Python edition's ports**, as
  described under Option B.
- **`impeccable detect` flags the teal and violet colours as an "AI
  palette".** Both are required by DESIGN.md (Signal Teal, and Leak Violet for
  accepted invalid input). I left them as they are.

## Backend changes I recommend but did not make

These are also in `docs/ux-revamp-plan.md` and `docs/ux-review.md`.

1. **Guarantee every selected case at least one request.** Done in the Java
   edition: `engine/VirtualUser.java` starts each user at a random case and
   then cycles through every case in order, so one user covers every case
   once it has sent as many requests as there are cases. (The Python engine
   picks a random case per request, `random.choice` in
   `locust_tests/dynamic_locustfile.py`.)
2. **Store the target path and the case payloads with each run.** The Java
   backend stores both for its engine (`TestRun.targetPath`, `RunCase`) but
   does not return them in the API. Returning them would give full URLs and
   Re-run from any browser or the CLI.
3. **Return findings and coverage counts in `/api/jobs`.**
4. **Add an endpoint to delete or filter runs.**
