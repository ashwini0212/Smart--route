# Phase 13: The performance pass — measuring the parts nobody had measured, and fixing what that found

## STEP 1: What we built

| Piece | Purpose |
|---|---|
| `scripts/perf/db_queries.py` | The application's 20 hottest queries under `EXPLAIN (ANALYZE, BUFFERS)`, optionally against a synthetic year of history inserted and rolled back; `--try-sql` tries an index candidate inside that same transaction |
| `scripts/perf/overhead.py` | One request taken apart: HTTP, authentication, a small read, the cache read and history write, and A* — each layer's cost as the difference from the one below it |
| `scripts/perf/load.py` | The same endpoints under N concurrent clients, which every other script here deliberately does not do |
| `V7__analytics_indexes.sql` | The two indexes those measurements earned, with the before/after numbers in the migration itself |
| `analytics.AnalyticsService` (changed) | The ETA-accuracy query rewritten as a `JOIN LATERAL`: 63 ms → 11 ms, with no index added |
| `events.SystemEventRepository` / `EventStreamService` (changed) | The event log is paged as a `Slice`, and the outbox's published total is an estimate — two sequential scans removed from endpoints that are polled |
| `common/web/SliceResponse.java` | The uncounted page shape, next to `PageResponse` which keeps the total for the lists that are small enough to count |
| `docs/benchmarks/phase-13-*.txt` | The raw output of every run quoted here |

The existing per-phase scripts (`route_latency.py`, `optimize_latency.py`, `assignment_workload.py`, `event_latency.py`, `live_latency.py`) were all re-run on the current code; two of them needed fixing first, which is covered in STEP 6.

## STEP 2: What was measured, and why those things

```
  a request                                    the database                     the algorithms
  ──────────                                   ────────────                     ──────────────
  overhead.py    layer by layer                db_queries.py   20 queries       JMH suite
  route_latency  endpoint, one client          --scale 100000  a year of data    (backend/benchmarks)
  optimize_…     3 to 20 stops                 --try-sql       index candidate
  assignment_…   dispatch of 100 orders        EXPLAIN ANALYZE plans and scans
  event_latency  commit → readable
  live_latency   position → browser frame
  load.py        8 clients at once
```

Phases 3 and 6 to 10 each measured their own piece, so the algorithms, the route API, assignment, events and the live stream already had numbers. Two things had never been measured at all: **what the database does** (no plan had ever been looked at) and **where the milliseconds in a request actually go**. Both turned out to contain the real findings, which is the argument for this phase existing rather than trusting the per-phase numbers.

## STEP 3: What the measurements found

### 3.1 Four analytics queries read the whole history table (fixed)
At the demo size every query the application still issues is under 3 ms and nothing is visible (the two it no longer issues, the outbox and event-log counts, cost 73 and 53 ms even there — see §3 of the raw output). With 100k orders and 405k history rows spread over a year — the shape this schema reaches after a few months of real use — the analytics endpoints each took 34–64 ms, every one of them a sequential scan of `order_status_history`, because nothing indexed the two columns they all filter on: the status that was reached and when. `V7__analytics_indexes.sql` adds `(to_status, changed_at)` on the history and `(created_at)` on orders:

| Query (100k orders, 7-day window) | Before | After |
|---|---|---|
| `/api/analytics/overview` counts by status | 34.5 ms | **1.3 ms** |
| `/api/analytics/throughput` per day | 45.4 ms | **1.4 ms** |
| `/api/analytics/overview` assignment→delivery | 41.8 ms | **6.4 ms** |
| `/api/analytics/overview` punctuality | 41.1 ms | **24.5 ms** |
| `/api/analytics/fleet` per driver | 45.1 ms | **35.9 ms** |
| `/api/analytics/eta-accuracy` | 63.5 ms | **10.8 ms** (this one also needed rewriting, 3.2) |
| Order list page count | 25.7 ms | **16.1 ms** |
| A driver's active deliveries | 4.6 ms | **2.9 ms** |

The two that improved least (`fleet`, `punctuality`) still scan `delivery_order` because they join every completion in the window to its order; at 100k orders that join is the cost, and an index cannot remove it.

### 3.2 One query needed rewriting, not indexing (fixed)
`/api/analytics/eta-accuracy` chose each pickup's assignment in a CTE and joined `assignment` back on the id it returned. The planner estimated the CTE at thousands of rows and hashed the whole 82k-row assignment table. Adding `(order_id, created_at DESC, id DESC)` to `assignment` changed *nothing*, which is the useful signal: the index was not the problem, the shape was. Written as a `JOIN LATERAL` it becomes a nested loop with memoization over the existing index — 63 ms → 11 ms, and the candidate index was thrown away. See ED-65.

### 3.3 Two endpoints counted a table on every call (fixed)
`GET /api/events` asked PostgreSQL to count 320k recorded events on every page view (43 ms), and `GET /api/events/outbox` counted the published rows (65 ms) — both sequential scans that get slower for the lifetime of the deployment, for totals that are already stale when they are rendered, because the relay publishes more rows while the response is being written. The log is now paged as a slice (`hasNext`, no total) and the published figure is PostgreSQL's own row estimate minus the exact pending backlog. The pending number — the one an operator acts on — was always cheap and still is, because the partial index holds only unpublished rows. See ED-64.

### 3.4 A* is the cheapest part of a routing request (not a problem; a correction)
Taking a request apart, on a warm JVM, 200 requests per step:

| Step | p50 | What it adds |
|---|---|---|
| `/actuator/health/liveness` | 1.10 ms | HTTP, Tomcat, the filter chain, and this client's own overhead |
| `/api/simulation/status` | 1.21 ms | **+0.11 ms** — the security chain and JWT verification |
| `/api/warehouses` | 2.26 ms | **+1.05 ms** — a small JPA read and its JSON |
| `/api/routes/fastest`, cached | 5.87 ms | **+3.61 ms** — the Redis read, 3 KB of geometry, and the history row this endpoint writes on every request |
| `/api/routes/fastest`, computed | 8.21 ms | **+2.34 ms** — A* over the 10k-node graph |

So the routing algorithm is about a quarter of a route request, and the single most expensive layer is the plumbing around the cache — of which the history `INSERT` is roughly 2–3 ms (a cached *alternatives* request, which stores nothing, is 3.4 ms against 6.2 ms for a cached route). Phase 6 left this open: bring the cached path under 5 ms by writing history asynchronously, or explain it. It is explained and kept synchronous — a history with holes in it is a worse thing to own than a 6 ms response. See ED-66.

### 3.5 The cold/warm difference was mostly the JVM, not the cache
The first route run in this phase reported a computed route at p50 18.2 ms and a cached one at 11.3 ms — the cache apparently saving 7 ms. Re-run after a few thousand more requests: 9.3 ms and 6.2 ms, a 3 ms saving. Nothing changed but JIT compilation. Phase 6's "the cache saves only ~3 ms at this size" was right, and the larger figure was an artifact of measuring a cold JVM. Both runs are in `docs/benchmarks/phase-13-api.txt`, in that order, because the comparison is the point.

## STEP 4: The numbers, as they stand now

All on one 4-core Intel Xeon @ 2.80 GHz with 15 GB RAM, with PostgreSQL 17, Redis and Kafka in Docker on the same host, the backend as a local jar, and the client on the same machine. The road network is the synthetic 100×100 grid (9,998 nodes, 35,677 edges) unless stated.

| Measurement | Result | Source |
|---|---|---|
| Fastest route, computed (A* + history write) | p50 9.3 ms, p95 15.1 ms | `route_latency.py` |
| Fastest route, cache hit | p50 6.2 ms, p95 9.4 ms | same run |
| Authenticated `GET /api/warehouses` (floor) | p50 3.6 ms | same run |
| Order of 12 stops, exact | p50 69 ms end to end, 2 ms of it the sequencing | `optimize_latency.py` |
| Order of 20 stops, heuristic | p50 96 ms | same run |
| Auto-dispatch of 100 orders | 2,146 ms (21 ms per order), 49 of 105 drivers used, busiest 4 | `assignment_workload.py` |
| Same with ETA only | 1,926 ms, 31 drivers used, busiest 8, mean pickup ETA 2.4 min against 2.8 | same run |
| Order change → event readable | p50 487 ms (500 ms relay interval) | `event_latency.py` |
| Driver position → dashboard frame | p50 457 ms, p95 705 ms, 12.3 frames/s, driver simulator on (frames counted, drivers not) | `live_latency.py` |
| Every hot query, demo database, idle | all ≤ 2.3 ms | `db_queries.py` |
| Analytics at 100k orders, after V7 | 1.3–35.9 ms | `db_queries.py --scale 100000` |

### Algorithms (JMH, whole suite re-run this phase)

See `docs/benchmarks/phase-13-jmh.json` for the full result set and `docs/benchmarks/phase-13-jmh.txt` for the run as it printed. Settings: 1 fork, 3 warm-up and 5 measurement iterations of 1 s, average time per operation.

| Benchmark | Result | Ratio |
|---|---|---|
| A\* against Dijkstra, local trip, 50×50 grid | 28 µs against 49 µs | 1.8× |
| A\* against Dijkstra, cross-city, 100×100 (10k nodes) | 1.20 ms against 1.95 ms | 1.6× |
| A\* against Dijkstra, cross-city, 200×200 (40k nodes) | 7.6 ms against 11.5 ms | 1.5× |
| k-d tree against a linear scan for snapping, 40k nodes | 0.75 µs against 5.8 ms | ~7,700× |
| One reversed Dijkstra against one search per driver, 100 drivers | 3.7 ms against 173 ms | 46× |
| Held-Karp exact ordering | 0.09 ms at 8 stops, 2.7 ms at 12, 14.5 ms at 14 | — |
| Nearest neighbour + 2-opt | 0.001 ms at 8 stops, 0.006 ms at 14 | ~2,400× faster at 14 |
| The cost matrix both of them need | 31.5 ms at 8 stops, 54.1 ms at 14 | — |

The last row is the one worth remembering: whichever ordering algorithm runs, the 2(n+1) graph searches that
build the distance matrix cost ten times more than the ordering itself at every size this endpoint allows.
Choosing exact against heuristic is therefore a choice about tour quality, not about latency, until about 14
stops — which is where the Phase 8 threshold of 12 came from, and this run agrees with it.

A\*'s advantage over Dijkstra shrinks as the graph grows (1.8× → 1.5×) because the haversine heuristic is
weak on a uniform grid where every edge is equivalent; on a real road network with motorways it would be
worth more. The error bars on the longer rows are wide (±15–65 %, and ±104 % on the 12-stop heuristic row) because four cores are shared with
everything else on this box, so the ratios are the result and the absolute numbers are this machine's.

### Under concurrent load

`route-fresh` means a new trip every request, so A* runs each time and the cache never helps. 15 seconds
per run, after the warm-up the earlier scripts had already given this JVM.

| Clients | Requests/s | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|
| 1 | 57.8 | 15.4 ms | 28.2 ms | 42.1 ms | none |
| 4 | 241.6 | 14.9 ms | 29.5 ms | 42.9 ms | none |
| 8 | 390.9 | 18.1 ms | 39.0 ms | 57.8 ms | none |
| 16 | 550.8 | 24.8 ms | 62.8 ms | 90.5 ms | none |

Throughput is linear to four clients (one per core) with latency flat, then rises sublinearly while latency
climbs: 16 clients get 2.3× the throughput of four for 1.7× the latency. Nothing failed at any level — no
timeouts, no connection errors, no 500s — so the pool sizes and the graph's immutable snapshots hold up under
this much concurrency. Other workloads, all at 8 clients:

| Workload | Requests/s | p50 | p95 |
|---|---|---|---|
| Route, cache hit (still writes history) | 714.1 | 10.0 ms | 19.2 ms |
| `GET /api/orders?status=CREATED` | 430.0 | 16.4 ms | 34.6 ms |
| `GET /api/analytics/overview?days=7` | 341.0 | 21.7 ms | 39.9 ms |
| Mixed (a third each of fresh, cached, orders) | 518.4 | 13.6 ms | 30.0 ms |

Phase 6 asked for exactly this measurement before deciding what to do about the history write, and the answer
it gives is "nothing": under eight concurrent clients the cached path is already serving 714 requests a second
on four shared cores, and the 2–3 ms the write costs is not what anyone would fix first.

## STEP 5: Tests

This phase changed three queries and two response shapes, so the tests that matter are the ones that already covered them, plus three new frontend tests for the slice contract:

| Test | What it pins down |
|---|---|
| `AnalyticsApiTest` (10, unchanged) | The rewritten ETA query returns the same numbers: same samples, same medians, and still one assignment per pickup when an order was assigned several times |
| `OutboxTest.theOutboxEndpointCountsWhatIsWaitingAndIsAdminOnly` | `pending` is still exact; `publishedEstimate` is present and a number — deliberately not asserted to a value, because it is an estimate |
| `KafkaEventFlowTest` | `/api/events` returns a slice: the content and `hasNext`, no total |
| `EventsPage.test.tsx` (3, new) | Paging an uncounted log says "page 2" and nothing about totals; Next is disabled when `hasNext` is false; the published figure is shown as "about"; a dispatcher never requests the outbox |
| `stubFetch` (changed) | The longest matching prefix wins, so `/api/events` no longer swallows `/api/events/outbox` — the bug that made the first version of the new test fail |

Totals: 388 backend, 72 frontend.

## STEP 6: What measuring got wrong first

Three of the measurements in this phase were wrong before they were right, and all three were only visible because the scripts print their conditions:

1. **A fixed trip seed.** `route_latency.py` generated its 300 trips from `Random(7)`, so the second run of the script measured, in the column labelled "first request (computed)", 300 cache hits left behind by the first run. The script now seeds from the clock and prints the seed, so a run can still be repeated on purpose but is cold by default.
2. **A health check as the floor.** The layer script started from `/actuator/health`, which measured 6 ms — slower than the authenticated endpoints above it, because that endpoint pings PostgreSQL, Redis and Kafka. The floor is `/actuator/health/liveness`.
3. **A rolled-back bulk insert leaves dead rows.** After the `--scale` runs, the demo database measured up to 20× slower on the same queries (0.4 ms → 17 ms) with the same plans. That was 160k dead tuples and the simulators' write load, not the queries; the numbers in this document were taken after `VACUUM` and with the simulators off, and the file says which runs were which.

A fourth was not a measurement error but a missed client: changing `/api/events` to a slice broke `event_latency.py`, which polled `totalElements`. The frontend was updated with the endpoint; a script in the same repository was not, and nothing failed until it ran.

## STEP 7: Review notes

- **Nothing was tuned that was not measured.** No connection-pool sizes, no JVM flags, no Hibernate batch settings: none of them appeared in any measurement as a limit, and changing them would have been decoration.
- **What is still slow, and known:** the two analytics queries that join every completion in the window to its order (36 ms and 25 ms at 100k orders), the delay sweep at 35–55 ms per driver (Phase 10, unchanged — it will not scale to hundreds of active drivers on one instance), and the cached route path at p95 9.4 ms against a p95 target of 5 ms — the p50 is 6.2 ms, and an earlier draft of this line compared that p50 to the p95 target, which understated the miss. The history write is 2–3 ms of it, so dropping the write would not reach the target either.
- **What this phase cannot tell you:** anything about a real road network. Every routing number here is the synthetic grid, where A* costs about 2 ms; on a city with millions of nodes the algorithm would dominate the request rather than the plumbing, and the cache would matter much more than it does here.
- **The scripts are not a load-testing framework.** `load.py` has no ramp-up, no think time, and no correction for coordinated omission, and it runs on the same four cores as the database it is loading. It is enough to see where latency starts climbing; it is not a capacity statement.

## Interview questions

1. Your analytics endpoint takes 40 ms and `EXPLAIN` shows a sequential scan over 400k rows. You add an index on the timestamp and it is still a sequential scan. What are the three most likely reasons?
2. Why is `(to_status, changed_at)` the right column order for `WHERE to_status = 'DELIVERED' AND changed_at BETWEEN a AND b`, and when would the opposite order be better?
3. A `count(*)` for a paginated endpoint costs 43 ms and grows forever. Give three ways to make that endpoint fast, and say what each one gives up.
4. What is the difference between `Page` and `Slice` in Spring Data, in terms of the SQL actually issued?
5. When is PostgreSQL's `reltuples` estimate good enough to show a user, and what makes it move?
6. You measure a request at 11 ms. You run the same measurement again on the same build and get 7 ms. What are you probably measuring, and how do you stop measuring it?
7. A cache hit saves 7 ms in your first measurement and 3 ms after warm-up. Which number goes in the README, and why?
8. Your benchmark generates random inputs from a fixed seed so runs are comparable. Why can that make a cache benchmark meaningless, and what do you do instead?
9. You insert 100k rows in a transaction, measure, and roll back. What have you left behind, and how would you notice it in the next measurement?
10. A route request spends 1 ms in HTTP, 1 ms in a read, 3.6 ms around the cache and 2.3 ms in A*. Where do you look first if you need the endpoint twice as fast, and what would you refuse to do?
