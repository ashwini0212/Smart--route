# Phase 8: Multi-stop optimization

## STEP 1: What we built

| Piece | Purpose |
|---|---|
| `algorithms.sequencing.CostMatrix` | Pairwise travel costs, asymmetric (one-way streets) and allowing `+∞` |
| `algorithms.sequencing.NearestNeighbourTour` | Greedy construction, O(n²) **[HEURISTIC]** |
| `algorithms.sequencing.TwoOpt` | Local search: reverse a segment while that helps **[HEURISTIC]** |
| `algorithms.sequencing.HeldKarp` | Exact shortest order by DP over subsets, O(n²·2ⁿ) |
| `algorithms.sequencing.StopSequencer` | Picks exact or heuristic by size |
| `routing.RouteOptimizationService` + `POST /api/routes/optimize` | Snap → cost matrix → order → legs → arrival times |
| `routing.RouteOptimizationLimiter` | 20 optimizations per minute per user, because the work is exponential |
| `assignment.DeliveryRouteService` + `GET /api/deliveries/route` | The driving order over a driver's own deliveries |
| `benchmarks.StopSequencingBenchmark`, `SequencingGapReport` | Run time (JMH) and how far from optimal the heuristic lands |

## STEP 2: Architecture

```
POST /api/routes/optimize {start, stops[], mode, strategy, returnToStart, departAt, capacity}
   │
   ▼
rate limit (20/min per user)                      the exact algorithm is exponential: a shared CPU needs a cap
   │
   ▼
snap start + every stop                           k-d tree, O(log V) each; > 300 m from a road → 422
   │
   ▼
cost matrix: one Dijkstra per point, each          (n+1) searches, not (n+1)²
stopping when all other points are settled         asymmetric: cost[i][j] ≠ cost[j][i] on one-way streets
   │
   ├─ any pair unreachable → 422 NO_ROUTE
   ▼
visiting order
   ├─ ≤ 12 stops (AUTO) or EXACT     → Held-Karp     O(n²·2ⁿ), optimal = true
   └─ more, or HEURISTIC             → nearest neighbour + 2-opt, optimal = false, labelled [HEURISTIC]
   │
   ▼
one A* per chosen leg → distance, time, polyline   n searches; this is what the map draws
   │
   ▼
arrival times = departAt + travel + service        a stop past its dueBy is listed in lateStops, never dropped
```

## STEP 3: The algorithms

### 3.1 Why this is hard
Ordering n stops to minimize travel is the travelling-salesman problem, which is NP-hard: no algorithm is
known that solves every instance in polynomial time. Brute force is O(n!) — 12 stops is 479 million orders.
So there are two honest options: an exact algorithm that is exponential but much better than n!, or a
heuristic with no guarantee. This phase implements both and always says which one answered.

### 3.2 Held-Karp (exact, O(n²·2ⁿ) time, O(n·2ⁿ) space)
The insight is that the cheapest way to "have visited this set of stops and now stand at stop i" does not
depend on the order in which the set was visited. So the state is (set, current stop), the set is a bitmask,
and each state extends by one stop:

```
best[S ∪ {j}][j] = min over i ∈ S of  best[S][i] + cost(i, j)
```

12 stops: 2¹² · 12 ≈ 49,000 states instead of 479 million orders. The price is memory: the table is
`n · 2ⁿ` doubles, which is why `MAX_STOPS = 16` (about 134 MB) is a hard limit rather than a tuning knob.

Correctness is not assumed: `HeldKarpTest` compares it against brute force over **every** permutation on
2–8 points, 20 random instances each, both open and closed tours, symmetric and asymmetric — 300+ instances
where the DP result must equal the true optimum to 1e-9.

### 3.3 Nearest neighbour + 2-opt (heuristic)
Nearest neighbour walks to the closest unvisited stop: O(n²), no guarantee, and its last leg is often a long
jump back across the city. 2-opt repairs exactly that: it reverses a segment whenever the reversal is
shorter, which removes crossings, and repeats until no single reversal helps. The result is *2-optimal*,
which is weaker than optimal: improving it further may require changing three or more legs at once.

On an asymmetric matrix a reversal also flips the direction of every leg inside the segment, so the usual
symmetric shortcut (compare two edges) is wrong; this implementation re-sums the reversed part. A test with
random asymmetric matrices checks that the reported cost is the real cost of the returned order.

### 3.4 Where the exact/heuristic line sits, and why
Measured (see STEP 6): exact costs 0.09 ms at 8 stops, 0.57 ms at 10, 2.9 ms at 12, 15 ms at 14 — roughly
×5 per two stops. The heuristic costs 0.001–0.005 ms throughout and lands on average 1.8–3.2 % above the
optimum, finding the optimum outright in about half the instances up to 12 stops. So `AUTO` uses the exact
algorithm up to **12** stops and the heuristic above it. That threshold is a measurement, not a guess, and
it is in one constant (`StopSequencer.DEFAULT_EXACT_LIMIT`).

The surprise in the numbers: building the cost matrix costs 35–46 ms, ten times the exact algorithm at 12
stops. For a trip of this size the choice of sequencing algorithm is not what the user waits for — the n+1
Dijkstra runs are. That is worth knowing before "optimizing the optimizer".

### 3.5 Capacity and time windows (FR-17)
- **Capacity is respected**: if the stops together weigh (or take up) more than the capacity given with the
  request, the answer is `422` naming the overflow. Nothing is silently dropped to make the numbers fit.
- **Time windows are reported, not enforced**: stops are ordered by travel cost, then arrival times are
  computed from `departAt` plus travel plus service time, and every stop that arrives after its `dueBy`
  appears in `lateStops` with how late it is. A route that cannot be made on time is still returned, because
  the dispatcher needs to see *which* promise is at risk. Ordering by travel time and then checking windows
  is not the same as solving for windows (a vehicle-routing problem with time windows); this phase does not
  claim to do that.

## STEP 4: Contracts

| Method | Path | Who | Result |
|---|---|---|---|
| POST | `/api/routes/optimize[?compare=true]` | any role (20/min) | visiting order, legs with geometry, arrival times, late stops |
| GET | `/api/deliveries/route?driverId=&mode=&strategy=&departAt=` | staff, viewer | the same for one driver's active deliveries |
| GET | `/api/deliveries/mine/route` | driver | their own |

Request: `start`, 1–20 `stops` (`label`, `location`, `weightKg`, `volumeM3`, `dueBy`, `serviceMinutes`),
`mode` (FASTEST default), `strategy` (AUTO default, or EXACT / HEURISTIC to force one), `returnToStart`,
`departAt`, `capacityKg`, `capacityM3`. Duplicate stop locations are rejected.

Response: `totalDistanceMeters`, `totalDurationSeconds`, `serviceSeconds`, `finishAt`, `algorithm`,
`optimal`, `algorithmSteps`, `sequencingMillis`, `visits[]` (sequence, stopIndex, label, arriveAt, departAt),
`legs[]` (distance, duration, `path` for the map), `lateStops[]`, `comparedTo`, `graphVersion`.

`?compare=true` runs the heuristic **and** the exact algorithm on the same matrix and returns the heuristic
result with the exact total in `comparedTo`, so a client can show the real gap instead of a claim about it.

## STEP 5: Tests (306 total, 41 new; `./mvnw verify` green)
- `HeldKarpTest` (14): brute-force equality on 2–8 points (20 instances each, open and closed), asymmetric
  costs, every stop visited once, any start point, impossible legs avoided rather than chosen, the 0/1-stop
  edge cases, the size limit, and the state count (2ⁿ, not n!).
- `TwoOptTest` (6): removes a crossing on a square, never worsens a tour and keeps every stop (50 random
  instances), the start stays fixed, the result really is 2-optimal (checked by trying every reversal),
  asymmetric cost accounting, `maxPasses`.
- `NearestNeighbourTourTest` (5): it does take the nearest stop; it is worse than optimal on a trap instance
  that 2-opt then fixes; unreachable stops leave an infinite cost instead of a dropped stop.
- `CostMatrixTest` (4): defensive copy, validation, direction-aware tour cost, completeness check.
- `RouteOptimizeApiTest` (8): stops ordered along a line; **the returned total equals the cheapest of all 120
  orders of 5 stops, with each leg's distance taken from `/api/routes/shortest`** (nothing trusts the
  optimizer's own numbers); the return leg; the heuristic is never better than exact and `comparedTo` matches;
  late stops flagged and nothing dropped; capacity refused with a clear message; validation, off-network
  stops, the exact-size refusal with the heuristic still accepting it; the per-user rate limit with
  `Retry-After` and another user unaffected.
- `DeliveryRouteApiTest` (4): a driver's three drops sequenced from the driver's position with service time;
  delivery windows that cannot be met flagged; a driver sees only their own route; clear 422/404 messages
  when there is nothing to route.

## STEP 6: Measurements
Full output: [docs/benchmarks/phase-8-sequencing.txt](../benchmarks/phase-8-sequencing.txt). Synthetic
10k-node city, 4-vCPU VM.

### How far from optimal is the heuristic? (30 random instances per size, real road travel times)

| Stops | Nearest neighbour | + 2-opt | Exact time | Heuristic time |
|---|---|---|---|---|
| 5 | mean 4.6 %, max 33.7 %, 18/30 optimal | mean 0.2 %, max 5.9 %, 29/30 optimal | 0.09 ms | 0.05 ms |
| 8 | mean 7.8 %, max 39.4 %, 5/30 | mean 2.0 %, max 9.8 %, 16/30 | 0.11 ms | 0.04 ms |
| 10 | mean 7.6 %, max 31.5 %, 4/30 | mean 3.2 %, max 18.8 %, 15/30 | 0.55 ms | 0.02 ms |
| 12 | mean 7.8 %, max 32.7 %, 2/30 | mean 1.9 %, max 9.1 %, 13/30 | 2.92 ms | 0.03 ms |
| 14 | mean 10.5 %, max 35.0 %, 0/30 | mean 1.8 %, max 9.4 %, 14/30 | 13.39 ms | 0.04 ms |

2-opt earns its place: it cuts the greedy gap from ~8 % to ~2 % for almost no time. But "mean 2 %" hides a
max of 19 %: on a single bad instance the heuristic can be a fifth longer than necessary, which is exactly
why the API reports `optimal: false` instead of calling the result "optimized".

### Algorithm run time (JMH, average time per call)

| Benchmark | 8 stops | 10 | 12 | 14 |
|---|---|---|---|---|
| Held-Karp exact | 0.088 ms | 0.572 ms | 2.903 ms | 15.2 ms |
| Nearest neighbour + 2-opt | 0.001 ms | 0.002 ms | 0.004 ms | 0.005 ms |
| Building the cost matrix (n+1 Dijkstras) | 35.2 ms | 34.8 ms | 46.8 ms | 45.8 ms |

### End-to-end API (one sequential client, 12 requests per size)

| Stops | p50 | p95 | of which sequencing (server-reported) |
|---|---|---|---|
| 3 | 32 ms | 36 ms | 0.0 ms |
| 10 | 80 ms | 83 ms | 0.0 ms |
| 12 | 93 ms | 104 ms | 2.5 ms |
| 16 (heuristic) | 109 ms | 117 ms | 0.0 ms |
| 20 (heuristic) | 133 ms | 158 ms | 0.0 ms |

**Reading these honestly:** request time grows with the number of points, not with the choice of algorithm,
because it is dominated by the 2(n+1) graph searches (matrix plus one per chosen leg). At 12 stops the exact
algorithm adds 2.5 ms to a 93 ms request. If this needed to be faster, the matrix is where to look —
caching legs between stop pairs, or an A* with a landmark heuristic — not the sequencing. The JMH matrix
numbers carry wide error bars on this shared VM, so treat them as the right order of magnitude rather than
precise values.

## STEP 7: Review notes (bugs found by running it)
- **Not measured:** the gap against visiting stops in creation order, which Phase 0 §1.3 listed as a success criterion. Only heuristic-against-exact was run, so nothing here claims how much better than the naive order either algorithm is. (Noted in the Phase 15 audit.)
- **Every optimize request answered 400.** Jackson 3 fails on a JSON `null` (or absent field) for a
  primitive by default, and `returnToStart` was a `boolean`. The field is now `Boolean` with
  `returnsToStart()` for the default, which also reads better: absent means "no return leg".
- **A validation rule that never ran.** `@AssertTrue` on a record method called `areStopsDistinct()` was
  silently ignored, so duplicate stops were accepted; Bean Validation only treats getter-shaped methods
  (`isX`, `getX`) as properties. Renaming it to `isEveryStopDistinct()` made it fire. The test that caught
  this asserted on the response message, not on the status alone, which is why it failed loudly.
- **A benchmark that measured the wrong thing.** The first version of `costMatrix` generated the city graph
  inside the measured method, so it reported ~17 ms of graph generation as matrix cost. Moving generation
  into `@Setup` changed the number to 35–46 ms. A benchmark is code and can be wrong like any other code.
- **`DEFAULT_EXACT_LIMIT` was 10 by guess**, then set to 12 once the gap report showed exact costs 2.9 ms
  there while the heuristic can be 19 % off.

## Interview questions
1. Why is ordering stops NP-hard, and what does Held-Karp actually save over trying all orders?
2. Explain the Held-Karp state. Why can the order within a visited set be forgotten?
3. Why is the limit 16 stops a memory limit rather than a time limit?
4. What does "2-optimal" mean, and why is it weaker than optimal? Give a tour that 2-opt cannot improve but
   that is not optimal.
5. Why does a 2-opt reversal need special handling on one-way streets?
6. The cost matrix needs n+1 searches, not (n+1)². How, and what does the early stop buy?
7. The heuristic's mean gap is ~2 % but its max is 19 %. Which number would you put in a README, and why?
8. Measurements show sequencing is 3 % of request time at 12 stops. What would you optimize first, and how
   would you check it worked?
9. Why are time windows reported rather than enforced? What would it take to actually plan around them?
10. Why is this endpoint rate-limited when the route endpoints are not?
11. Where would caching help here, and what would the cache key be?
12. A driver has 30 stops. What does this API do, and what would you change for that size?
