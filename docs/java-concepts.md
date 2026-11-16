# Java concepts in this project (viva guide)

Each row names the exact place in the code and says, in plain words, what the concept
does there and why we used it. Paths are under `backend/src/main/java/com/elevate/`
unless they say otherwise.

## Object-oriented programming

| Concept | Where | What it does here, and why |
|---|---|---|
| Encapsulation | `model/AbstractRun.java` (`changeStatus`) | Fields are private and there is no `setStatus`. The only way to change a run's status is `changeStatus`, which also stamps `completedAt` when the status is terminal, so the two can never disagree. |
| Encapsulation | `engine/CaseRecorder.java` | The histogram recorder and counters are private; callers can only `record(...)` or `drainInterval()`. This keeps the thread-safety rules inside one class instead of trusting every caller. |
| Inheritance | `model/AbstractRun.java` → `MeasuredRun.java` → `TestRun.java`, `SplitGroup.java`; `AbstractRun` → `RepeatSeries.java` | Every run has an id, target, users and status, so those live once in a `@MappedSuperclass`. Single runs and split groups can also be baselines and have an SLO, so they share a second level. Each subclass adds only what is its own. |
| Inheritance (template) | `analysis/edgecase/AttackPayloadGenerator.java` → `SqlInjectionGenerator`, `XssGenerator` | The abstract class does the loop (one case per payload, text fields only) in a `final` method; subclasses only supply the prefix, payloads and wording. Adding a new attack type is three small methods. |
| Interfaces | `analysis/edgecase/EdgeCaseGenerator.java` | One method, `generate(field, type, sample)`, implemented by seven classes. `EdgeCaseSuite` depends only on the interface, so it never needs to know which kinds of edge case exist. |
| Interfaces (functional) | `analysis/target/TargetGuard.HostResolver`, `engine/EngineSpec.StepJudge`, `cli/CliRunner.Sleeper` | Small interfaces with one method, so we can pass a lambda. Tests pass a fake DNS resolver, a fake judge or a no-op sleeper instead of the real thing. |
| Polymorphism | `EdgeCaseSuite.generate` calls `g.generate(...)` on a `List<EdgeCaseGenerator>` | The same call runs different code for each strategy at run time (dynamic dispatch). |
| Polymorphism + sealed types | `dto/RunView.java` (`JobView`, `GroupView`, `SeriesView`), `engine/LoadProfile.java` (`Fixed`, `Steps`) | `sealed` interfaces list every allowed implementation, so the compiler knows the full set. `LoadEngine` checks `spec.profile() instanceof LoadProfile.Steps plan` (pattern matching) to decide whether to track steps. |
| Abstraction | `engine/LoadEngine.java` | Callers see `run()` and `stop()`. Virtual threads, schedulers and histograms are hidden behind them. |

## Design patterns

| Pattern | Where | What it does here, and why |
|---|---|---|
| Strategy | `analysis/edgecase/*Generator.java` + `EdgeCaseSuite` | Each kind of edge case (missing field, null, wrong type, boundary, very long string, SQL injection, XSS) is its own strategy class. The suite runs every strategy over every field; the order of the list fixes the order of the 27 cases, which matches the Python version exactly. |
| Builder | `engine/EngineSpec.Builder` | An engine has many settings, most with defaults (think time, timeout, headers, clock, step judge). The builder lets callers set only what they need and gives back one immutable, validated record. |
| Factory method | `EdgeCaseSuite.standard()`, `StartRunResponse.run/series/capacity`, `RunCase.edgeCase/page`, `MockResponse.message` | Named static methods that create objects in a known-good state, so callers do not need to know the constructor details. |
| Factory (class) | `service/EngineSpecFactory.java` | Turns a stored job (`JobPlan`) into an `EngineSpec`: API or website templates, fixed or step profile, and the early-stop judge. |
| Template method | `AttackPayloadGenerator.generate` | See inheritance above. |
| Repository | `repository/*Repository.java` | Spring Data interfaces; Spring writes the implementation from method names like `findTop50ByGroupIsNullOrderByCreatedAtDesc`. |
| Dependency injection | every `@Service`, `@Component`, `@RestController` constructor | Classes ask for what they need in the constructor; Spring supplies it. No `new` of services in business code, so tests can pass mocks. |
| Adapter | `analysis/stats/LocustCsv.java` | Our structured results are adapted to the CSV format the React frontend already parses, so the frontend did not need rewriting. |
| Observer-like callback | `StepTracker` → `EngineSpec.StepJudge` → `LoadEngine.finish(EARLY_STOP)` | When a capacity step ends, the tracker calls the judge; if it says stop, the engine ends early. |

## Language features

| Concept | Where | What it does here, and why |
|---|---|---|
| Records | all of `dto/`, `analysis/stats/CaseMeasurement.java`, `engine/EngineResult.java`, `model/CapacityPlan.java` | Immutable data carriers with constructor, accessors, `equals` and `hashCode` generated. Compact constructors add defaults (`StartRunRequest`: 5 users, spawn rate 1, 30 s) and defensive copies (`RunResults`: `List.copyOf`). |
| Records as JPA embeddables | `model/CapacityPlan.java`, `SloSettings.java`, `FailureTally.java`, `TimelinePoint.java` | Hibernate 6.2+ can store a record's components as columns of the owning table. Value objects stay immutable even inside entities. |
| Enums with behaviour | `analysis/slo/SloThreshold.java`, `analysis/outcome/Outcome.java`, `model/RunStatus.java` | Each SLO threshold carries its label, unit, allowed range and a `Function<RunMetrics, Double>` that reads its metric, so evaluation is one generic loop. `RunStatus.isTerminal()` keeps the rule in one place. |
| Switch expressions and pattern matching | `ResponseClassifier.classify`, `WrongTypeGenerator`, `RunCommand.set` (`case Integer i ->`), `RunExecutor.cancel` | Exhaustive `switch` on enums and types, with no fall-through bugs. |
| Generics | `AttributeConverter<List<StepRecord>, String>` (`model/StepRecordsConverter.java`), `JpaRepository<TestRun, String>`, `TypeReference<>` | Type-safe containers and converters: the compiler checks what goes in and out. |
| Collections | `EnumMap` (`Slo`, `RunRecorder`), `LinkedHashMap` (keeps case order in `ResultMerger`), `TreeMap` (timeline seconds sorted), `TreeSet` (sorted unknown labels), `ConcurrentHashMap` (`RunRegistry`, `CaseRecorder`) | Each collection is chosen for one property: enum keys, insertion order, sorted order or thread safety. |
| Streams and lambdas | `FailureSummarizer`, `CaseComparison`, `StatsTable.of` (`reduce(CaseMeasurement::plus)`), `OutcomeAnalysis` | Grouping, filtering and summing the results reads like the Python version, without loops and temporary lists. |
| Optional | `TargetGuard.check` returns `Optional<String>`; `RunSnapshots.find` | "No problem" or "not found" is explicit in the type, not a `null` someone might forget to check. |
| Text blocks | repository `@Query` strings, test sitemap XML | Multi-line strings without `+` and escapes. |

## Exceptions

| Concept | Where | What it does here, and why |
|---|---|---|
| Custom exception hierarchy | `exception/ApiException.java` → `BadRequestException` → `TargetBlockedException`, `OwnershipNotConfirmedException`; `NotFoundException`, `TooManyActiveRunsException` | Each error carries its HTTP status and extra JSON fields (`blocked_target`, `busy`, `missing_confirmation`). Services throw them; they never build HTTP responses. |
| Global handler | `exception/GlobalExceptionHandler.java` (`@RestControllerAdvice`) | One place turns every exception into `{"error": ...}`: our own errors keep their status, Bean Validation and bad JSON become 400, unknown paths 404, anything else 500 and logged. |
| Checked vs unchecked | `GuardedHttpClient.BlockedRedirectException extends IOException` vs `CliException extends RuntimeException` | A blocked redirect is a kind of I/O failure the probe must handle, so it is checked. CLI errors bubble up to one catch that prints them and exits with code 2. |
| try-with-resources | `LoginHandler.handle` (`try (exchange)`), tests (`try (var executor = ...)`) | Resources are closed even when an exception is thrown. |

## Concurrency (the load engine)

| Concept | Where | What it does here, and why |
|---|---|---|
| Virtual threads | `LoadEngine.run` (`Executors.newVirtualThreadPerTaskExecutor()`), `RunExecutor` | Every simulated user is a virtual thread. Blocking in `HttpClient.send` or `Thread.sleep` parks the virtual thread and frees the carrier thread, so 1,000 users need a handful of OS threads. Code stays simple blocking code. |
| Executors and scheduling | `LoadEngine` (`newSingleThreadScheduledExecutor` with a virtual thread factory) | One scheduler runs the spawn tick (every 100 ms) and the sampler (every second) at a fixed rate. |
| Wait-free recording | `CaseRecorder` (HdrHistogram `Recorder`) | Many users record latencies at once with no lock. The single sampler thread swaps out the interval histogram once a second (`getIntervalHistogram`), so nothing is lost or counted twice. |
| Atomics and adders | `LongAdder` and `LongAccumulator` in `CaseRecorder`, `AtomicInteger` active users, `AtomicReference<StopReason>` and `AtomicBoolean` in `LoadEngine` | Counters updated by many threads without locks. `compareAndSet` makes `finish(...)` happen exactly once even if duration end, cancel and early stop race. |
| volatile | `LoadEngine.running`, `RunRegistry.shuttingDown` | A write by one thread is seen by all others at once; users check `running` before each request. |
| CountDownLatch | `LoadEngine.done` | `run()` waits on the latch; `finish()` releases it from whichever thread ends the run. |
| Interruption | `LoadEngine.run` → `users.shutdownNow()`; `VirtualUser` catches `InterruptedException` | Stopping interrupts every user, which wakes them from `sleep` and `HttpClient.send`; `awaitTermination` then proves none are left. |
| Thread confinement | `IntervalSampler`, `StepTracker` | Their fields are used by one thread at a time (the scheduler, then the engine thread after `awaitTermination`), so they need no locks. |
| Semaphore | `service/ActiveRunLimiter.java` | `MAX_ACTIVE_RUNS` permits; `tryAcquire()` is atomic, so two start requests can never both take the last slot. |
| synchronized | `mock-target/.../FixedWindowRateLimiter.tryAcquire` | The window second and its count must change together, so a short lock is simpler than two atomics. |
| Futures | `RunExecutor.execute` (`threads.submit(...)`, `Future.get()`) | A split run starts one engine per child in parallel and waits for all of them. |
| Happens-before | `RunRegistry.register` / `requestCancel` | Each method writes its own map first and reads the other's second, so a cancel and a start that race always see each other. |

## Networking and I/O

| Concept | Where | What it does here, and why |
|---|---|---|
| `java.net.http.HttpClient` | `engine/LoadEngine.prepare`, `VirtualUser`, `analysis/probe/GuardedHttpClient`, `cli/BackendClient` | One immutable, thread-safe client per engine; requests are prepared once and reused. The probe client never follows redirects itself, so every hop can be checked by the SSRF guard first. |
| `com.sun.net.httpserver.HttpServer` | `mock-target/.../MockTargetServer` | The mock target with a fixed thread pool of 8 workers: when more requests come than it can handle, they queue and latency climbs, which gives the capacity finder a real breaking point. |
| `InetAddress` | `analysis/target/AddressClassifier`, `Cidr` | DNS resolution and address checks (loopback, site-local, multicast) plus our own CIDR matching for the ranges Java has no method for. |
| Secure XML parsing | `analysis/probe/SitemapReader.parse` | DOCTYPE declarations are refused, which blocks XML external entity (XXE) attacks from a hostile sitemap. |

## Spring

| Concept | Where | What it does here, and why |
|---|---|---|
| Dependency injection, beans | `config/BeanConfig.java` (`@Bean` methods), constructor injection everywhere | The analysis classes are plain Java; `BeanConfig` turns them into beans. Constructor injection makes dependencies visible and the classes testable. |
| REST controllers | `controller/*` (`@RestController`, `@PostMapping`, `@ResponseStatus(ACCEPTED)`) | Thin HTTP layer; the start endpoints return 202 because runs continue in the background. |
| Bean Validation | `dto/StartRunRequest.java` (`@Min`, `@Max`, `@NotBlank`), `dto/UrlRequest.java`, `@Valid` in controllers | Range checks are declared on the record; messages match the Python API ("users must be between 1 and 1000"). |
| JPA / Hibernate | `model/*` (`@Entity`, `@MappedSuperclass`, `@OneToMany`, `@ElementCollection`, `@Embedded`, `@Convert`, `@Lob`) | Runs, groups, cases and results are entities; histograms are stored compressed in `@Lob` columns; JSON lists use an `AttributeConverter`. |
| Spring Data | `repository/*` (`@Query`, `@Modifying(clearAutomatically = true)`) | Derived queries and bulk updates (baseline reset, marking runs interrupted). |
| Transactions | `service/RunStore.java` (`@Transactional`), `RunSnapshots` (`readOnly = true`) | Each lifecycle step is one short transaction; engine threads never hold entities. `open-in-view` is off. |
| Configuration properties | `config/AppProperties.java` (`@ConfigurationProperties` record with `@DefaultValue`) | Typed settings from `application.yml`, each overridable by an environment variable. |
| Profiles | `src/test/resources/application-test.yml`, `@ActiveProfiles("test")` | Tests use an in-memory database and short think times; production uses the H2 file database. |
| Lifecycle hooks | `service/StaleRunRecovery.java` (`SmartInitializingSingleton`), `RunExecutor.shutdown` (`@PreDestroy`) | Leftover runs are marked interrupted before the web server takes requests; on shutdown engines are stopped and results saved. |
| CORS | `config/WebConfig.java` | Only origins listed in `CORS_ALLOWED_ORIGINS` may call the API from a browser. |

## Testing

| Concept | Where | What it does here, and why |
|---|---|---|
| JUnit 5 parameterized tests | `OutcomeClassificationTest` (`@CsvSource`), `TargetGuardTest`, `SloTest`, `CapacityTest` | One test method checks a whole table of inputs and expected answers. |
| Nested tests | `EdgeCaseGeneratorsTest` (`@Nested` per strategy) | One group per edge-case strategy, with readable names in the report. |
| Mockito | `LoadTestServiceTest` (`@Mock`, `ArgumentCaptor`, `verifyNoInteractions`), `RunControlTest`, `CliTest` | Services are tested without a database or network: we check what they store and that nothing is stored when a request is refused. |
| MockMvc / `@WebMvcTest` | `controller/*Test.java` with `@MockitoBean` | Real Spring MVC (JSON, validation, exception handler, CORS) without starting a server; checks status codes and the exact JSON field names. |
| Integration tests | `it/EngineAgainstMockIT`, `it/ApiAgainstMockIT` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`) | Start the Java mock on a random port and run real load tests of a few seconds; assert that the three deliberate bugs are found. Run by Maven Failsafe. |
| Coverage | `backend/pom.xml` (JaCoCo `check`) | The build fails below 80% line coverage on service, engine and analysis. |

---

## 25 likely viva questions

1. **Why did you write your own load engine instead of using Locust or Gatling?**
   The course asked for a Java implementation, and owning the engine let us classify
   every response against its expected answer and record exact histograms per case.
   `LoadEngine` is about 200 lines: a scheduler for spawning and sampling, virtual
   threads for users, `HttpClient` for requests.

2. **What is a virtual thread and why is it a good fit here?**
   A thread managed by the JVM, not the OS. When it blocks on I/O it is unmounted from
   its carrier thread, so thousands can wait on HTTP responses at almost no cost. A
   virtual user is naturally blocking code ("send, wait, think, repeat"), so we keep that
   simple style without a thread per user in the OS.

3. **How do you stop a run cleanly?**
   `LoadEngine.stop()` sets the volatile `running` flag to false and counts down the
   latch. `run()` then shuts down the scheduler, calls `shutdownNow()` on the user
   executor (which interrupts `sleep` and `HttpClient.send`) and waits with
   `awaitTermination`. If threads did not stop, it throws; tests check the run ends
   within a few seconds.

4. **Why HdrHistogram? Why not store all latencies in a list?**
   A list grows with every request and percentiles need sorting. A histogram has fixed
   size, records in constant time, and gives any percentile to 3 significant digits.
   Most importantly, two histograms can be added exactly, which is what split runs need.

5. **How is recording thread-safe without locks?**
   Each case has an HdrHistogram `Recorder`: writers call `recordValue`, which is
   wait-free. Once a second the single sampler thread calls `getIntervalHistogram()`,
   which atomically swaps the active histogram for an empty one and returns the old one.
   Counters are `LongAdder`s, which spread contention over cells.

6. **Why a `Recorder` instead of one histogram per thread merged at the end?**
   We also need a per-second timeline. Per-thread histograms would only give totals at
   the end, and with 1,000 users mean 1,000 histograms. The recorder gives exact
   interval histograms and the cumulative histogram is built by adding them.

7. **Is the merged p95 of a split run really exact?**
   Yes, up to the histogram's resolution (±0.1%). `ResultMerger` adds the children's
   histograms, which is the same as recording every request in one histogram.
   `StatsTest.mergedPercentilesAreExact` proves it and shows the Python approach
   (averaging each child's p95) gives a different answer.

8. **What does "expected outcome" mean, and how is a response classified?**
   Each case expects 2xx (valid input) or 4xx (invalid input). `ResponseClassifier`
   says 429 is always rate limiting, 5xx always a server error, no response a connection
   error, a 2xx on a "reject" case is accepted invalid input, and a 4xx on an "accept"
   case is rejected valid input.

9. **Explain the Strategy pattern in your edge-case generator.**
   `EdgeCaseGenerator` is the strategy interface with seven implementations.
   `EdgeCaseSuite` holds a list of strategies and applies each to each field. Adding a new
   kind of edge case means one new class and one line in `EdgeCaseSuite.standard()`;
   nothing else changes.

10. **Where do you use inheritance and why not only interfaces?**
    In the entities (`AbstractRun` → `MeasuredRun` → `TestRun`/`SplitGroup`) to share
    mapped columns, and in `AttackPayloadGenerator`, where subclasses share the actual
    loop. Interfaces are used where only behaviour is shared (`EdgeCaseGenerator`).

11. **Why are DTOs records?**
    They are pure data: immutable, with `equals`/`hashCode`/`toString` generated. The
    compact constructor of `StartRunRequest` fills in defaults before Bean Validation
    runs. Jackson serializes them in snake_case through the global naming strategy.

12. **How does the frontend keep working although the backend changed language?**
    We wrote `docs/api-contract.md` from the Python routes and the frontend's fetch
    calls and kept every field name. The frontend parses Locust's CSV formats, so
    `LocustCsv` writes our structured results in those formats.

13. **What is SSRF and how do you prevent it?**
    Server-side request forgery: tricking the server into requesting internal addresses.
    `TargetGuard` resolves the host and refuses private, loopback, link-local and
    multicast addresses (unless allowed) and always refuses cloud metadata addresses.
    `GuardedHttpClient` follows redirects by hand so each hop is checked before connecting.

14. **Why does the probe never use POST?**
    POST can change data on the user's server. HEAD, GET and OPTIONS are read-only by
    definition. A POST-only endpoint answers them with 405, which we treat as "reachable,
    method not allowed".

15. **How does MAX_ACTIVE_RUNS work, and why a Semaphore?**
    `ActiveRunLimiter` holds a semaphore with that many permits. A start calls
    `tryAcquire()`; if it fails the API returns 429 with `busy: true`. The background task
    releases the permit in a `finally` block. `tryAcquire` is atomic, so there is no
    check-then-act race.

16. **What happens to running tests when the backend restarts?**
    On shutdown `@PreDestroy` stops the engines and stores their partial results as
    "interrupted". After a crash, `StaleRunRecovery` marks every queued or running run as
    interrupted before the web server accepts requests.

17. **How does the capacity finder decide the breaking point?**
    The engine runs a step profile; `StepTracker` records each step with its own
    histogram. `CapacityEvaluator` judges each complete step against the per-step SLO
    (p95, average, server errors, unexpected answers). The highest passing step with all
    lower steps passing is "max sustainable", and the first failing step is the breaking
    point. With early stop the engine ends at that step's boundary.

18. **How is the SLO evaluated?**
    `SloThreshold` is an enum; each constant knows its metric and whether it is a
    maximum or minimum. `SloEvaluator` loops over the thresholds that were set: PASS if
    all measured ones hold, FAIL if any fails, no verdict if nothing could be measured.

19. **How do you persist histograms?**
    `Histograms.encode` uses HdrHistogram's compressed byte encoding into a `@Lob`
    column (`CaseResult.histogram`, `TimelinePoint.histogram`). Reading decodes them, so
    a split group can merge its children exactly at any time.

20. **What is `@Transactional(readOnly = true)` doing in `RunSnapshots`?**
    It opens one read-only transaction for loading a run with its lazy collections
    (cases, results, timeline), so lazy loading works without open-session-in-view, and
    tells Hibernate no flush is needed.

21. **Why did you set `clearAutomatically = true` on `@Modifying` queries?**
    Bulk updates bypass the persistence context. Clearing it afterwards makes the next
    `findById` reload the row instead of returning a stale cached entity (for example
    the old baseline flag).

22. **How do you test a controller without starting a server?**
    `@WebMvcTest` loads only the MVC layer; services are replaced with `@MockitoBean`.
    `MockMvc` sends requests and `jsonPath` checks status codes and exact field names,
    including error bodies like `{"error": ..., "blocked_target": true}`.

23. **What do the integration tests prove that unit tests cannot?**
    That everything works together over real HTTP: the engine against the real mock
    finds exactly the three deliberate bugs, the API runs single, split, cancelled and
    capacity runs, 429 is returned while busy, and the CLI exits 0 or 1 as expected.

24. **What bug did testing find in your own engine?**
    Users first started at case *i* for user *i*. With 15 users and 27 cases a 10-second
    run never reached the last cases, so both 500 bugs were missed. Users now start at a
    random case and then cycle. A second one: capacity steps were judged one second late;
    they now close at the step boundary.

25. **What are the limits of your design?**
    Everything runs in one JVM, so split runs are parallel on one machine, not
    distributed. Percentiles are exact only to 0.1%. Load-test requests do not follow
    redirects. The H2 database is per backend, so two backends would not share history.
