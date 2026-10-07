# Talking about SmartRoute

Written for the person who built it, to use in a CV, an application, a screening call and an interview. Every
number here is traceable to a run in `docs/benchmarks/` or to a script in `scripts/perf/` — if a figure is not
in this file, it was not measured, and the honest answer in a conversation is "I did not measure that".

---

## The one-line version

> A logistics dispatch platform built around real graph algorithms: Java 21 and Spring Boot 4, PostgreSQL,
> Redis and Kafka behind a React dashboard, with A\*, Dijkstra, k-d trees and exact and heuristic stop
> sequencing doing the actual work, and every claim in the repository backed by a benchmark that can be re-run.

## The thirty-second version (screening call)

SmartRoute is a delivery dispatch system. Orders come in, drivers are ranked against them by road-network
travel time and workload, and the dispatcher sees the fleet move on a live map while delays raise themselves
as alerts.

What makes it worth looking at is that the hard parts are implemented, not imported. Routing is A\* over an
adjacency-list graph I built, with k-d tree snapping and a cache keyed on a traffic fingerprint. Driver ETAs
for one order are a single reversed Dijkstra rather than one search per driver, which is 46 times faster at a
hundred drivers. Stop ordering is exact Held–Karp up to twelve stops and a nearest-neighbour plus 2-opt
heuristic above that, and the API always says which one ran.

And it is honest. Nothing in it is called real-time, because I measured the delay and it is half a second by
design. The driver simulator is labelled in the payload, the read model and the stream, so simulated movement
can never be read as a measurement.

## The two-minute version (first technical conversation)

Add to the above, in this order:

1. **Shape.** A Spring Boot modular monolith with enforced module boundaries (ArchUnit fails the build on a
   cycle or on a repository leaking out of its package), PostgreSQL with Flyway, Redis for the route cache and
   the live-position read model, Kafka for domain events, and a React + TypeScript dashboard.
2. **Correctness under concurrency.** Assignment takes a row lock on the order, then the driver, in that order,
   so two dispatchers assigning the same order serialize instead of double-booking.
3. **Events.** Nothing is published from inside business logic: a transactional outbox writes the event in the
   same transaction as the data change, and a relay publishes it. Consumers claim an event id before handling
   it, so a repeated delivery is handled once, and failures end in a dead-letter topic per topic.
4. **What measuring found.** The database had never been profiled until Phase 13. At the demo size everything
   was fast; at 100k orders, four analytics queries were sequential scans at 34–64 ms. Two indexes and one
   rewritten query fixed it, and two endpoints stopped counting whole tables on every call.
5. **What is not there.** One instance, no horizontal scaling, no real road data committed, no real drivers —
   and the README says so in its own section.

## The deep-dive version (systems interview)

Pick whichever of these the interviewer pulls on; each has a document behind it.

| Thread | The interesting part | Where |
|---|---|---|
| A\* against Dijkstra | 1.5–1.8× on this graph, and *less* as the graph grows, because a haversine heuristic has little to say on a uniform grid. The honest version of "A\* is faster". | [Phase 2](phases/phase-2-graph-core.md), [Phase 3](phases/phase-3-road-network-benchmarks.md) |
| One search for many drivers | ETAs to one pickup from 100 drivers: 3.7 ms with one Dijkstra on the reversed graph, 173 ms with one search per driver. | [Phase 7](phases/phase-7-assignment.md) |
| Exact or heuristic, and saying which | Held–Karp is O(n²·2ⁿ); measured, 14 stops is 14.5 ms and the threshold sits at 12. Above it, NN + 2-opt averages 2 % above optimal, worst case 19 %. | [Phase 8](phases/phase-8-multi-stop-optimization.md) |
| The outbox | Why a `@Transactional` Kafka send is a lie, and what the alternative costs: p50 487 ms from commit to readable at the default relay interval. | [Phase 9](phases/phase-9-kafka-events.md) |
| The bug only load could find | Making the relay ten times faster made the live map *worse* — Spring's scheduler runs one thread by default, and the relay, the sweep, the heartbeat and the simulators were sharing it. | [Phase 10](phases/phase-10-live-tracking.md) |
| Numbers that carry their definitions | Every analytics response ships the sentence each number was computed with, so "on-time rate" cannot quietly mean something else on the dashboard than in the query. | [Phase 12](phases/phase-12-observability-analytics.md) |
| Profiling a request, not guessing | 1.1 ms HTTP, +0.1 auth, +1.1 a small read, +3.6 cache and geometry and the history write, +2.3 A\*. The algorithm is the cheapest part. | [Phase 13](phases/phase-13-performance.md) |
| An assistant that cannot act | Six read-only tools, and an architecture test that fails the build if one of them can write. The prompt is not the guard. | [Phase 14](phases/phase-14-assistant.md) |

---

## CV bullets

Use three to five. Each is traceable; keep the number and the condition together.

- Built a logistics dispatch platform (Java 21, Spring Boot 4, PostgreSQL, Redis, Kafka, React/TypeScript)
  implementing routing from first principles: adjacency-list graph, BFS/DFS, Dijkstra, A\*, k-d tree nearest
  neighbour and exact and heuristic stop sequencing, with a JMH suite for every one of them.
- Cut driver-ETA computation for a 100-driver fleet from 173 ms to 3.7 ms (46×) by replacing one shortest-path
  search per driver with a single Dijkstra on the reversed road graph.
- Profiled the database at a synthetic 100,000 orders and 405,000 status-history rows; added two indexes and
  rewrote one query as a `JOIN LATERAL`, taking the analytics endpoints from 34–64 ms sequential scans to
  1–36 ms and one query from 63 ms to 11 ms.
- Designed an exact/heuristic switch for multi-stop routing with a measured threshold: Held–Karp to 12 stops,
  nearest neighbour + 2-opt above it (2 % above optimal on average, 19 % worst case, 700× faster), with the API
  always reporting which algorithm produced a result.
- Delivered order events through a transactional outbox to Kafka with idempotent consumers and per-topic
  dead-letter handling, and measured the end-to-end delay (p50 487 ms) rather than describing the system as
  real-time.
- Wrote 429 backend tests (248 of them against real PostgreSQL and Redis in Testcontainers, 10 also against
  real Kafka) and 77 frontend tests, with ArchUnit rules that fail the build on a module cycle or on an
  endpoint that states no authorization rule.

### Bullets to avoid

"AI-powered logistics optimization platform", "real-time tracking", "scalable microservices", "optimized
delivery routes by 40 %". Each is either false here or unmeasured. The measured versions above are more
convincing to anyone who can tell the difference, and safe with anyone who cannot.

---

## A ten-minute demo

1. `docker compose up`, log in as the dispatcher. Say that the data is fictional and the city is synthetic.
2. **Orders → one order → candidates.** Show the ranked drivers with their ETAs, and point at the exclusion
   reasons: this is the same ranking the auto-dispatcher uses, not a display.
3. **Route planner.** Compute a cross-city route, then the same one again, and point at the cache. Add stops
   until the exact algorithm hands over to the heuristic, and show the label that says which ran.
4. **Live map** with `SIMULATE_DRIVERS=true`. Open it, then immediately point at the `SIMULATION` badge in the
   header: this movement is generated, and everything it produces is labelled all the way through the stream.
5. **Analytics.** Pick any card and read the definition printed under the number.
6. **Admin → events.** Show the outbox depth and the event log, and say what the relay interval buys and costs.
7. Finish on the README's Limitations section. Volunteering the limits is the point of the demo.

---

## Interview preparation

### The questions that actually get asked

**"Why a monolith?"** One team, one deployment, and boundaries enforced by a test instead of by a network. The
modules are separated well enough that extracting routing or events would be a refactor rather than a rewrite,
and nothing in the measurements says the boundary needs to be a network call yet.

**"Is this real-time?"** No, and it says so. Commit to readable is p50 487 ms, almost all of it the outbox
relay's polling interval. I could make it 59 ms by polling every 50 ms, and measured that too — but polling
that fast with one scheduler thread made the live map worse, which is how I found a real bug.

**"How do you know A\* is faster?"** JMH, 1 fork, 3 warm-up and 5 measurement iterations, on a shared 4-core
box with wide error bars on the longer benchmarks — so I quote the ratios between rows, not the absolute
numbers. 1.5–1.8×, and the advantage shrinks as the grid grows because the heuristic is weak on a uniform grid.

**"What would break first under load?"** The delay sweep: it costs 35–55 ms per driver on one instance and will
not survive hundreds of active drivers. After that, the single-instance SSE fan-out. Neither is hidden; both
are in the README's limitations.

**"What is the hardest bug you found?"** Making the outbox relay faster made the live map slower. Spring's
default scheduler is one thread, and the relay, the tracking sweep, the heartbeat and the simulators were
queueing behind each other: p95 went from 219 ms to 5.5 seconds. Only a measurement against the running stack
showed it; every test passed throughout.

**"Where does the AI part come in?"** It is one optional feature and it is off by default. It answers questions
by calling six read-only tools and separates what it read from what it suggests. It has no tool that can write,
and an architecture test fails the build if one appears — I did not want the prompt to be the security
boundary. I have never run it against the live API from this repository, so I cannot tell you how good its
answers are, and the repository does not claim anything about them.

**"What would you do differently?"** Profile the database in week one rather than in Phase 13. Everything was
fast at demo size and four queries were sequential scans at realistic size; finding that earlier would have
changed two schema decisions rather than adding indexes afterwards.

**"What are you not proud of?"** The road data is synthetic, because the sandbox could not reach the OSM API —
the import script is written and tested, but no real extract is committed. And the Phase 0 target of a cached
route at p95 under 5 ms was never met: p95 is 9 ms. The history write is 2–3 ms of it, so even dropping that
would not reach the target — and an earlier draft of my own README hid that by comparing the p50 to it.

### Going deeper

Each phase document ends with ten questions on its own material, from BFS complexity to outbox semantics to
what a prompt injection could and could not do. Working through those in order is the preparation:

[Phase 2](phases/phase-2-graph-core.md) · [Phase 3](phases/phase-3-road-network-benchmarks.md) ·
[Phase 4](phases/phase-4-domain-database.md) · [Phase 5](phases/phase-5-security.md) ·
[Phase 6](phases/phase-6-routing-api.md) · [Phase 7](phases/phase-7-assignment.md) ·
[Phase 8](phases/phase-8-multi-stop-optimization.md) · [Phase 9](phases/phase-9-kafka-events.md) ·
[Phase 10](phases/phase-10-live-tracking.md) · [Phase 11](phases/phase-11-frontend.md) ·
[Phase 12](phases/phase-12-observability-analytics.md) · [Phase 13](phases/phase-13-performance.md) ·
[Phase 14](phases/phase-14-assistant.md)

### The decisions file is the differentiator

`docs/engineering-decisions.md` holds 72 decisions, each with the alternative that was rejected and the price
paid for the one that was chosen. In an interview, "I chose X" is ordinary; "I chose X over Y, and here is what
it costs me" is the answer that ends the question. Pick three before the interview — ED-40 (the outbox),
ED-65 (rewriting a query instead of indexing it) and ED-68 (the assistant's read-only limit as a build rule)
cover a lot of ground between them.

---

## Things to say first, not when caught

- The city is **synthetic** unless a road dataset is configured, and no real OSM extract is committed.
- Driver movement and traffic are **simulators**, off by default, labelled everywhere they appear.
- All seed data is **fictional**. No real person's data is in this project.
- Every performance number is **this machine** — a shared 4-core VM also running the database — and the scripts
  that produced them print the conditions they ran under.
- It runs as **one instance**. Nothing here has been tested horizontally, and the parts that would break first
  are named.
- The assistant has **never been run against the live API** from this repository.
