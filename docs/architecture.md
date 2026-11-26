# Architecture

## Components

```mermaid
flowchart LR
    Browser["React frontend<br/>(frontend/, Vite)"]
    CLI["CLI<br/>com.elevate.cli<br/>(same jar: run --config)"]
    subgraph Backend["Spring Boot backend (backend/)"]
        direction TB
        C["controller<br/>REST endpoints"]
        S["service<br/>validation, run lifecycle,<br/>read models"]
        E["engine<br/>LoadEngine, VirtualUser,<br/>CaseRecorder, IntervalSampler"]
        A["analysis<br/>guard, probe, edge cases,<br/>outcomes, SLO, capacity, compare"]
        R["repository<br/>Spring Data JPA"]
        X["exception<br/>@RestControllerAdvice"]
        C --> S
        S --> E
        S --> A
        E --> A
        S --> R
        C -.errors.-> X
    end
    DB[("H2 file database<br/>data/")]
    Mock["Java mock target<br/>(mock-target/, HttpServer)"]
    Target["Any HTTP target"]

    Browser -- "REST / JSON" --> C
    CLI -- "REST / JSON" --> C
    R --> DB
    E -- "java.net.http<br/>virtual threads" --> Mock
    E --> Target
    A -- "probe: HEAD/GET/OPTIONS" --> Target
```

- The **controller** layer only maps HTTP to service calls; all JSON is snake_case
  (`spring.jackson.property-naming-strategy: SNAKE_CASE`) and every DTO is a record.
- The **service** layer validates requests, stores runs, runs them on virtual threads
  (`RunExecutor`), and builds the read models (`RunViewService`, `BaselineService`,
  `ReportService`).
- The **engine** knows nothing about Spring or JPA: it gets an `EngineSpec` and returns an
  `EngineResult`.
- The **analysis** package is plain Java with no framework dependency: every rule from
  the Python version lives here and is unit-tested directly.

## Engine and analysis classes

```mermaid
classDiagram
    direction LR
    class LoadEngine {
        -EngineSpec spec
        -RunRecorder recorder
        -volatile boolean running
        +run() EngineResult
        +stop()
    }
    class EngineSpec {
        <<record>>
        +templates
        +profile
        +thinkTime
        +builder() Builder
    }
    class LoadProfile {
        <<sealed interface>>
        +targetUsers(elapsed) int
        +totalDuration() Duration
    }
    class Fixed { <<record>> }
    class Steps { <<record>> }
    class VirtualUser {
        +run()
    }
    class RunRecorder {
        -List~CaseRecorder~ cases
        +record(index, status, micros, bytes, error)
    }
    class CaseRecorder {
        -Recorder latencies
        -LongAdder requests
        +record() Outcome
        +drainInterval() Histogram
    }
    class IntervalSampler {
        +sample()
        +finish() RunResults
    }
    class StepTracker {
        +add(interval...)
        +records() List~StepRecord~
    }
    class EngineResult { <<record>> }

    LoadEngine --> EngineSpec
    EngineSpec --> LoadProfile
    LoadProfile <|.. Fixed
    LoadProfile <|.. Steps
    LoadEngine --> RunRecorder
    LoadEngine ..> VirtualUser : starts on virtual threads
    VirtualUser --> RunRecorder
    RunRecorder --> CaseRecorder
    LoadEngine --> IntervalSampler
    IntervalSampler --> CaseRecorder : drains once a second
    IntervalSampler --> StepTracker
    LoadEngine ..> EngineResult

    class ResponseClassifier {
        +classify(status, expectation)$ Outcome
    }
    class Outcome { <<enum>> }
    CaseRecorder ..> ResponseClassifier

    class EdgeCaseGenerator {
        <<interface>>
        +generate(field, type, sample) List~EdgeCase~
    }
    class AttackPayloadGenerator { <<abstract>> }
    class MissingFieldGenerator
    class NullValueGenerator
    class WrongTypeGenerator
    class BoundaryValueGenerator
    class VeryLongStringGenerator
    class SqlInjectionGenerator
    class XssGenerator
    class EdgeCaseSuite {
        +standard()$ EdgeCaseSuite
        +generate(sample) List~EdgeCase~
    }
    EdgeCaseGenerator <|.. MissingFieldGenerator
    EdgeCaseGenerator <|.. NullValueGenerator
    EdgeCaseGenerator <|.. WrongTypeGenerator
    EdgeCaseGenerator <|.. BoundaryValueGenerator
    EdgeCaseGenerator <|.. VeryLongStringGenerator
    EdgeCaseGenerator <|.. AttackPayloadGenerator
    AttackPayloadGenerator <|-- SqlInjectionGenerator
    AttackPayloadGenerator <|-- XssGenerator
    EdgeCaseSuite o-- EdgeCaseGenerator

    class ResultMerger { +merge(parts)$ RunResults }
    class StatsTable { <<record>> }
    class SloEvaluator { +evaluate(slo, metrics)$ SloVerdict }
    class CapacityEvaluator { +evaluate(steps, plan, slo)$ CapacityResult }
    class TargetGuard { +check(url) Optional~String~ }
    ResultMerger ..> StatsTable
    CapacityEvaluator ..> SloEvaluator
```

## A load test run

```mermaid
sequenceDiagram
    autonumber
    actor U as Browser / CLI
    participant C as LoadTestController
    participant S as LoadTestService
    participant L as ActiveRunLimiter
    participant DB as RunStore (JPA)
    participant X as RunExecutor
    participant E as LoadEngine
    participant V as VirtualUser (×N)
    participant T as Target

    U->>C: POST /api/start-load-test
    C->>S: startApiTest(request) [Bean Validation done]
    S->>S: ownership, TargetGuard, headers, SLO, capacity plan
    S->>L: acquire()  (429 if MAX_ACTIVE_RUNS reached)
    S->>DB: save TestRun (queued) with its cases
    S->>X: launch(run, headers)
    C-->>U: 202 {job_id, status: started}
    X->>DB: status running
    X->>E: run()  (on a virtual thread)
    loop every 100 ms until the duration has passed
        E->>V: start users at spawn_rate
    end
    par each virtual user
        V->>T: HTTP request (java.net.http)
        T-->>V: response
        V->>E: record latency + outcome (wait-free)
        V->>V: think 0.5–2 s
    end
    loop every second
        E->>E: IntervalSampler drains histograms → timeline
    end
    U->>C: GET /api/load-test-status/{id} (every 2 s)
    C-->>U: running
    E-->>X: EngineResult (users stopped, threads joined)
    X->>DB: store results, status completed
    X->>L: release()
    U->>C: GET /api/load-test-status/{id}
    C-->>U: stats/failures/history CSVs, outcome summary, SLO verdict
```

A run above 50 users follows the same path with a `SplitGroup`: the executor starts
one engine per child on its own virtual thread, waits for all of them, and the status
endpoint merges the children's histograms. A cancel (`POST /api/cancel/{id}`) calls
`LoadEngine.stop()`: the engine ends at once and keeps the partial results.

## Deployment and queue mode

The default deployment runs load tests inside the API process. Queue mode
(`USE_SQS=true`) is implemented but, as in the Python version, **not deployed**: the
worker manifest and image exist and CI does not apply or build them.

```mermaid
flowchart LR
    subgraph K8s["Kubernetes (k8s/)"]
        SVC["Service load-tester-api<br/>NodePort :8080"]
        API["Deployment load-tester-api<br/>backend image, USE_SQS=false<br/>ALLOW_PRIVATE_TARGETS=false"]
        W["Deployment load-tester-worker<br/>(not deployed)<br/>java -jar load-tester.jar worker"]
        PVC[("PVC load-tester-db-pvc<br/>H2 file in /data")]
        SVC --> API
        API --> PVC
        W --> PVC
    end
    SQS[["AWS SQS queue<br/>SQS_QUEUE_URL"]]
    API -. "queue mode: JobMessage (JSON)" .-> SQS
    SQS -. "long poll, run, delete" .-> W
```

```mermaid
sequenceDiagram
    autonumber
    participant U as Browser / CLI
    participant A as API (RunExecutor)
    participant Q as JobQueue (SqsJobQueue)
    participant W as Worker (QueueWorker)
    participant R as JobRunner + LoadEngine
    participant DB as Shared H2 (AUTO_SERVER)

    U->>A: POST /api/start-load-test
    A->>DB: save TestRun (queued)
    A->>Q: send(JobMessage.of(plan))
    A-->>U: 202 {job_id}
    loop every 10 s (long poll)
        W->>Q: receive(1, 10 s)
    end
    Q-->>W: JobMessage
    W->>R: run(message.toPlan())
    R->>DB: running → results, completed
    W->>Q: delete(receipt)
    A->>DB: poll status until terminal (holds the MAX_ACTIVE_RUNS slot)
    U->>A: GET /api/load-test-status/{id}
    A-->>U: results from the shared database
```

Queue classes (`com.elevate.queue`): `JobQueue` (interface), `SqsJobQueue` (AWS SDK v2),
`JobMessage` (Python's payload keys), `QueueWorker` (poll → run → delete, also on failure),
`WorkerMode` (the `worker` entry point: no web server, queue mode on), `QueueConfig`
(beans only when `USE_SQS=true`). `JobRunner` is shared by local execution and the worker.
