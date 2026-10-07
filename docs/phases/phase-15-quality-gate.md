# Phase 15: The quality gate — three audits, and what each one found

> Status: complete. This is the last phase in [the plan](../phase-0-plan.md).

The earlier phases each reviewed their own work. This one reviewed the whole repository from three directions
at once, the way a reviewer who had never seen it would: **security** (what can a caller do that they should
not?), **honesty** (does every claim in the docs trace to code or a run?), and **code quality** (what is dead,
duplicated, racy or untested?). Each audit was a separate read-only pass that reported findings with
`file:line` evidence and changed nothing. Every finding was then checked by hand before anything was fixed,
and two of them turned out to be wrong — they are listed at the end with why.

---

## STEP 1: What we built

Nothing new for a user. What changed:

- **Security:** one endpoint's role tightened, the API document made closable, a per-user cap on live
  streams, the authorization rule extended to reads, a Content-Security-Policy, Dependabot and a CI
  dependency audit, and a prompt-injection paragraph in the assistant's system prompt.
- **Correctness:** a race in the driver geo index that could silently stop a driver getting work, an
  unclosed HTTP client, SSE streams dropped instead of ended at shutdown, an O(V) walk on every map load,
  a guard that would have answered 500 instead of 422.
- **Honesty:** a public endpoint that called the product "intelligent real-time", a README sentence that
  understated an unmet target by comparing a p50 to a p95 goal, numbers cited against the wrong phase,
  stale contracts, and test totals that did not add up.
- **Tests:** the password boundaries, the geo index's recovery path, the closed API document, the
  stream cap.

## STEP 2: How the audits were run

Three agents in parallel, each told to read the repository, report with evidence, and change nothing.
Read-only mattered: an audit that fixes as it goes produces a diff nobody can check against the finding.
Every finding below was reproduced or read before it was acted on.

| Audit | Scope | Findings acted on | Found wrong |
|---|---|---|---|
| Security | every controller, `SecurityConfig`, the SSE path, logs, config, nginx, CI | 9 | 0 |
| Honesty | README, all phase docs, the plan, decisions, benchmark files, surefire reports | 16 | 0 |
| Code quality | dead code, races, duplication, resource lifecycles, hot paths, test gaps | 17 | 2 |

## STEP 3: What each finding was, and the fix

### Security

| Finding | Fix | Evidence it holds |
|---|---|---|
| `GET /api/admin/assignment-config` was `STAFF` by annotation, `ADMIN` by URL rule | annotation is `ADMIN` | `AssignmentApiTest.onlyAdminsChangeTheWeights` (dispatcher now 403 on the read) |
| The architecture rule required `@PreAuthorize` on writes only, so a read with no rule passed silently | the rule covers `@GetMapping`; deliberate open reads say `Access.ANY_USER` | `ArchitectureTest.endpointsDeclareAnAuthorizationRule` (ED-73) |
| `/v3/api-docs` was always public | `API_DOCS_PUBLIC` (default `true`) switches it to `ADMIN` | `ApiDocsClosedTest`, `AuthorizationTest.theApiDocumentIsOpenHereAndCanBeClosed` (ED-74) |
| One token could open unlimited SSE connections | four per user, the fifth closes the oldest | `LiveStreamApiTest.oneUserCannotHoldUnlimitedConnections`, `…twoUsersEachGetTheirOwnAllowance` (ED-75) |
| The bootstrap admin's email was logged | logs the user id | read |
| No Content-Security-Policy | `frontend/nginx.conf` sets one; OpenStreetMap tiles allowed in `img-src` | read |
| No dependency updates or audit | `.github/dependabot.yml`; `npm audit --omit=dev --audit-level=high` in CI | CI |
| Customer-typed text reaches the assistant's context | a "Text that came from outside" section tells it to report such text, never follow it, and flag it under UNCERTAINTY | read; no live model call was made, so how well a model follows it is **not** claimed |

What the security audit checked and found clean: no secret in Git history or config defaults, no SQL built
from strings, no stack trace in any response body, no role read from the client, no `dangerouslySetInnerHTML`.

### Code quality

| Finding | Fix |
|---|---|
| `DriverLocationIndex.rebuild()` could run on two request threads at once; one's `delete` between the other's `delete` and `add` left drivers out of the index, and `stale = false` at the end could overwrite a failure that arrived mid-rebuild. A driver missing from the index is never offered as a candidate, with no error anywhere | `synchronized`, and `stale` is cleared first. New `DriverLocationIndexTest` (3) covers the recovery path, which had no test |
| `AnthropicClient` was built inside the model bean, so Spring never closed its connection pool | the client is its own `@Bean` |
| `LiveStream.closeAll()` said it ran at shutdown; nothing called it | `@PreDestroy` |
| `GET /api/routing/network` walked every node per request for bounds that never change within a version | bounds computed once per network version in `RoadNetwork` |
| `EventStreamService` and `LiveStream` called `Instant.now()` beside (or instead of) the injected `Clock` | `clock.instant()` |
| `OrderService.transition` threw `IllegalArgumentException`, which maps to a 500 | `ApiException.businessRule` (422) |
| `OutboxEvent.lastError` was written and never read | surfaced as a `failing` count on the outbox status (ED-77) |
| Dead: `ToolArgs.requiredNumber`, `requiredDecimal`, `DriverRepository.findByStatusIn` | deleted |
| Two rate-limiter classes identical but for a number | `PerUserLimiter` (ED-76) |
| `Graph.reversed()` and `mapEdges()` duplicated their loop | one private `rebuild` |
| The order and event text filters fired a request per keystroke | `useDebouncedValue` (300 ms) on the query key only |
| The live map rebuilt every marker's style object on every frame | constant styles, a memoised `DriverMarker` |
| Password length boundaries had no test: bytes for the 72-byte BCrypt limit, code points for the minimum | `PasswordPolicyTest` (3) — fails if either is "simplified" to `String.length()` |
| Perf scripts: files and connections not closed; `load.py` crashed with `IndexError` on a run where nothing completed | `with`, `close()`, a zero-request report |
| A concurrency test's pool leaked on a failing assertion | `try/finally` + `shutdownNow()` |
| Stale comments (`SchedulingConfig`, `useLiveStream`) | corrected |

### Honesty

| Finding | Fix |
|---|---|
| `info.app.description` — public at `/actuator/info` — said "Intelligent real-time" | the OpenAPI wording, with a comment saying why |
| README: "302 tests on real PostgreSQL, Redis and Kafka" — only some use containers, one class uses Kafka | the split, counted from the surefire reports per class (the audit's own split was off by one: it listed `ForwardedClientIpTest` as container-free, and it imports `TestcontainersConfiguration`) |
| README and Phase 13 compared the cached route's **p50** (6 ms) to a **p95** target (5 ms), so "the history write stands between 6 and 5 ms" understated the miss: p95 is 9.4 ms, and without the write it would still be ~7 | corrected in both, with a note that the earlier wording was wrong |
| "12.3 frames a second from 120 simulated drivers" — the script counts frames, not drivers | driver count removed |
| "At the demo size every query is under 3 ms" — the same run shows two at 53 and 73 ms (the ones Phase 13 retired) | qualified |
| README quoted Phase 13 re-runs but linked to phases whose tables show the original runs | both runs cited |
| Test totals: Phase 12 said 389 (388 is consistent); Phase 9 + Phase 10's "new" count is off by one | 388; the off-by-one is noted rather than guessed at |
| Phase 9's outbox contract (`published`) and events contract (a page) were stale after Phase 13 | updated |
| Phase 11's `published` figure and the browser run's markers presented without the estimate / `[SIMULATION]` labels | labelled |
| Phase 13's error-bar range was narrower than its own JSON | ±15–65 %, and ±104 % on one row |
| Phase 0 promised a creation-order baseline for stop ordering; it was never run | stated as not measured in Phase 8 |
| Phase 0 promised the scale limits would be in the decisions file | ED-78 |
| Phase 0's persona row said "Optimized" | the labelled wording |
| Phase 12 and ED-59 described a query Phase 13 rewrote | pointed at ED-65 |
| Phase 12's "not here" list omitted consumer lag | added, marked unverified |

## STEP 4: Contract changes

| Endpoint | Change |
|---|---|
| `GET /api/admin/assignment-config` | `ADMIN` only (was any staff) |
| `GET /api/events/outbox` | adds `failing` |
| `GET /v3/api-docs`, `/swagger-ui.html` | `ADMIN` only when `API_DOCS_PUBLIC=false` |
| `GET /api/tracking/stream` | a user's fifth connection completes their oldest |

## STEP 5: Tests (429 backend, 77 frontend; 11 + 0 new)

New: `ApiDocsClosedTest` (2), `PasswordPolicyTest` (3), `DriverLocationIndexTest` (3),
`AuthorizationTest.theApiDocumentIsOpenHereAndCanBeClosed`, and `LiveStreamApiTest`'s two cap tests. One
assertion added to `AssignmentApiTest.onlyAdminsChangeTheWeights`. The frontend count is unchanged; the
existing page tests cover the debounced filters and the memoised map because both still render the same.

## STEP 6: Two findings that were wrong

- **"`TrafficRecalculationTrigger` is referenced by no test."** True by name, false in effect:
  `DeliveryWatchTest.aDelayThatGetsMuchWorseIsReportedAgain` changes the traffic and calls no sweep, then
  asserts a second alert — which only the trigger can produce. Nothing added.
- **"`EventConsumersIdempotencyTest` leaks its pool on a failing assertion."** Its pool is shut down and
  awaited before any assertion runs. The `AssignmentConcurrencyTest` half of the same finding was right and
  is fixed.

## STEP 7: Review notes

- **A test that was wrong about arithmetic.** The first draft of `PasswordPolicyTest` asserted that 25
  two-byte characters plus one were "seventy-five bytes" and should be rejected. They are 51. The test failed,
  which is the reason to run a new test before trusting it.
- **A test that found how Redis behaves.** Removing a geo set's only member deletes the key, so the index
  rebuilt itself on the next lookup through the "key missing" path rather than staying short a driver. The
  test now asserts the set's size directly.
- **A YAML value with a colon in it.** The corrected `info.app.description` read `Logistics and route platform: shortest…`, and an unquoted `: ` inside a YAML scalar is a mapping. Every Spring context failed to load — 248 errors from one line of config. It is quoted now. The application would not have booted either, so the full suite is what caught a change that looked like documentation.
- **Same-email users.** `users.token(Role.DISPATCHER)` creates a user with a fixed email, so calling it twice
  in one test fails. The new assertion reuses one token.

## Interview questions

1. Why did a read endpoint with no `@PreAuthorize` pass the architecture test before this phase, and why is
   an explicit `ANY_USER` better than the default it falls back to?
2. Two threads call `rebuild()` at once: walk through the interleaving that loses drivers. Why does clearing
   `stale` *first* fix the second race and not just move it?
3. Spring closed nothing when the client was built inside another bean's factory method. How does Spring
   decide what to call on shutdown, and how would you check what it inferred?
4. Why is the cached route's p95 the right number to compare to the target, and what did comparing the p50
   hide?
5. The outbox now reports a count of failing events, not the error message. Argue for and against putting the
   message in the response.
6. A debounce on the query key versus a debounce on the input's `onChange`: which one makes typing feel slow,
   and why?
7. An audit found two things that were wrong. What is the cost of acting on audit findings without checking
   them, and what is the cost of checking every one?
