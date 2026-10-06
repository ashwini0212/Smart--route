# Phase 6: Routing API, traffic snapshots and Redis cache

## STEP 1: What we built

| Piece | Purpose |
|---|---|
| `routing.RoadNetworkProvider` | Loads the road network at startup (CSV dataset, or the **synthetic** city by default), keeps the largest SCC, builds the k-d tree, and swaps in a new snapshot when traffic changes |
| `routing.RouteEngine` | Snap → cache lookup → A* → cache store, with a Micrometer timer per search |
| `routing.RouteCache` | Redis cache-aside, keys by network fingerprint and node pair, 30 s back-off when Redis fails |
| `routing.RouteHistoryService` + `V3__route_history.sql` | Every shortest/fastest result is stored and can be reopened |
| `algorithms.AlternativeRoutes` | Up to k different routes (penalty method) **[HEURISTIC]** |
| `Graph.mapEdges` | Copy-on-write edge transformation, used to apply traffic |
| `scripts/perf/route_latency.py` | The latency measurement below, reproducible |

## STEP 2: Architecture

```
POST /api/routes/fastest {from, to}
   │
   ▼
RouteEngine ── network = provider.current()          one volatile read; this snapshot is used to the end
   │           snap(from), snap(to)                  k-d tree, O(log V); > 300 m from a road → 422
   │           key = route:<fingerprint>:FASTEST:<fromNode>:<toNode>
   ├──► Redis GET key ──hit──► return (cached=true)
   │        │ miss / error (→ back-off 30 s)
   ▼        ▼
   A* on network.graph (time heuristic)               optimal; O((V+E) log V) worst case
   │
   ├──► Redis SET key (TTL 10 min)
   ▼
RouteHistoryService: INSERT route_record              id returned to the client
```

```
Traffic update (ADMIN, or the simulator in Phase 10)
   freeFlow graph ──mapEdges(time × multiplier)──► new Graph ──► new RoadNetwork(version+1, new fingerprint)
                                                                   │
                                     AtomicReference.set ◄─────────┘   readers never lock, never see half an update
```

## STEP 3: Design decisions explained

### 3.1 Why snapshots instead of a lock
A route search reads tens of thousands of edges. With mutable weights, a traffic update in the middle of a search would make it read some old and some new weights, and the "optimal" route would be optimal for no real state of the city. With immutable snapshots each request takes one reference at the start; writers build a complete new graph and swap the reference. Reads cost nothing extra, writes cost O(V + E) (a few milliseconds for 36k edges). `RouteApiTest.readersStayConsistentWhileTrafficIsSwappedConcurrently` runs 200 searches on 8 threads while traffic is replaced 20 times.

### 3.2 Traffic can only slow roads down
Multipliers are limited to [1, 10]. Besides being realistic, this keeps A* correct: the time heuristic divides straight-line distance by the fastest speed on the graph, and slowing edges can only make that bound looser, never wrong. (The heuristic is recomputed for every snapshot anyway.)

### 3.3 Cache keys and invalidation
- **Node ids, not coordinates.** Two users asking from points 2 m apart snap to the same intersection and share the entry (tested). Raw coordinates would almost never repeat.
- **Fingerprint, not deletion.** The key starts with a hash of the dataset plus the current traffic. When traffic changes, new requests use a new key; old entries are simply never read again and expire after 10 minutes. There is no "delete all keys of the old version" step that could be half-done. Returning to an earlier traffic state reproduces the same fingerprint, so its entries become valid again (tested).
- **Version vs fingerprint.** `graphVersion` (1, 2, 3, ...) is shown to users and counts changes in this instance; the fingerprint is content-based so several instances with the same data share cache entries.

### 3.4 Redis is optional
Every Redis error is treated as a miss. The first full-stack test with Redis stopped showed **0.52 s per request**: each request waited for a 250 ms timeout on the read and again on the write. Fix: after an error, bypass Redis for 30 s (a minimal circuit breaker). Measured after the fix: the first request still pays the timeout (0.84 s), the next ones take 35–74 ms. Readiness only checks the database, so a Redis outage doesn't take the app out of service; `/actuator/health` honestly reports DOWN.

### 3.5 Alternatives [HEURISTIC]
After the optimal route, the edges it uses get their weight multiplied by 1.4 and A* runs again, which pushes the search onto other roads. A candidate is kept only if its real length is within 1.4× of the optimum and at most 70 % of it overlaps any route already kept. The API labels these `"optimal": false, "algorithm": "penalty alternative [HEURISTIC]"`. Yen's k-shortest-paths was rejected because on a grid the next-shortest paths differ by a single block.

### 3.6 History
Each shortest/fastest response gets an id and is stored with its path as a `DOUBLE PRECISION[]`. Staff and viewers can open any stored route; other users only their own, and someone else's id answers **404, not 403**, so ids can't be probed. Alternatives aren't stored.

## STEP 4: Contracts

| Method | Path | Who | Result |
|---|---|---|---|
| POST | `/api/routes/shortest` | any role | `RouteResponse` (stored) |
| POST | `/api/routes/fastest` | any role | `RouteResponse` with current traffic (stored) |
| POST | `/api/routes/alternatives` | any role | up to `count` (1–3) routes, best first |
| GET | `/api/routes/{id}` | own; staff/viewer: any | stored route |
| GET | `/api/routes` | any role | own history, newest first |
| GET | `/api/routing/network` | any role | source, `synthetic` flag, size, bounds, version, segments with traffic |
| PUT | `/api/routing/traffic` | ADMIN | replace all multipliers; returns the new network version |

`RouteResponse`: `id, mode, distanceMeters, durationSeconds, path [[lat, lon], ...], from/to {nodeId, latitude, longitude, snapDistanceMeters}, algorithm, optimal, nodesSettled, graphVersion, cached, createdAt`.

New error codes (422): `LOCATION_OFF_NETWORK` (message format: "Start (lat, lon) is N m from the nearest road; the limit is 300 m") and `NO_ROUTE`.

## STEP 5: Tests (234 total, 29 new)
- `AlternativeRoutesTest` (8): optimal first, stretch and overlap limits hold, time mode, no alternative on a single road, too-long detours rejected, unreachable, same node, parameter validation.
- `GraphTest` (+2): `mapEdges` leaves the original untouched; endpoints can't change.
- `RoadNetworkProviderTest` (5): synthetic load, new version per update, old snapshot unchanged, fingerprint depends on content, multiplier 1 = no traffic, invalid multipliers/segments rejected.
- `RouteCacheResilienceTest` (2): unreachable Redis → miss, no exception, back-off skips Redis, retries after the back-off.
- `RouteApiTest` (13, real Redis): API result equals Dijkstra's cost on the same nodes; fastest ≤ shortest in time and shortest ≤ fastest in distance; nearby request is a cache hit with the same answer; jamming the fastest route raises its duration and changes the path, with no stale cache read; admin-only traffic; off-network and invalid input; same point; alternatives labelled; history privacy; stored = returned; network endpoint says synthetic; concurrent reads during swaps.

## STEP 6: Measurements
Full output: [docs/benchmarks/phase-6-route-api.txt](../benchmarks/phase-6-route-api.txt). One sequential client, docker compose on the 4-vCPU VM, synthetic 10k-node city, 300 random trips.

| Request | p50 | p95 |
|---|---|---|
| Fastest route, computed (A* + history insert) | 12.0 ms | 18.9 ms |
| Fastest route, cache hit (+ history insert) | 9.2 ms | 14.7 ms |
| Shortest route, computed | 10.3 ms | 16.2 ms |
| Shortest route, cache hit | 7.2 ms | 9.8 ms |
| 3 alternatives, computed | 9.3 ms | 25.1 ms |
| 3 alternatives, cache hit (nothing stored) | 4.7 ms | 6.6 ms |
| `GET /api/warehouses` (baseline: auth + one query) | 4.1 ms | 6.8 ms |

**Reading these honestly:**
- Against the Phase 0 targets: route p95 < 50 ms is **met** (19 ms). Cached route p95 < 5 ms is **not met** end to end (9.8–14.7 ms). The cache lookup is not the problem: a trivial authenticated GET already costs 4–7 ms here, and the history INSERT adds about 5 ms (compare cached alternatives, which store nothing: p95 6.6 ms).
- On a 10k-node city A* is so fast (Phase 3: ~1–2 ms cross-city) that the cache saves only ~3 ms per request. It saves more for alternatives (each is several A* runs: p95 25 → 6.6 ms) and would matter more on a real, larger map. I'm keeping it because it is cheap and correct, not because it was needed at this size.
- What would bring the cached path under 5 ms: write history asynchronously (or make storing opt-in), and measure again in Phase 13 under concurrent load instead of one sequential client.

## STEP 7: Review notes
- **Redis outage made every request 0.5 s slower** (two timeouts). Fixed with the back-off; measured before and after.
- **`RoutingProperties` wasn't registered**, so the context failed to start in the first test run; fixed with `@EnableConfigurationProperties`.
- **Shared state between tests.** The network provider and Redis are singletons that outlive one test, so a traffic test could slow down routes in the next test and a cached entry could make "first request" assertions flaky. Every API test now starts with an empty Redis and free-flow traffic.

## Interview questions
1. Why do traffic updates build a new graph instead of changing edge weights in place? What does that cost, and when would it stop being acceptable?
2. Why does the cache key use snapped node ids instead of the request coordinates?
3. How are stale routes avoided after a traffic change without deleting any keys? What is the memory cost?
4. Why can't traffic multipliers be below 1 in this design? What breaks in A* if a road becomes faster than the "max speed" used by the heuristic?
5. Redis goes down. Walk through what happens to a route request, and why the back-off was needed.
6. Why does someone else's route return 404 instead of 403?
7. The cache saves only ~3 ms here. Was it worth adding? When would it matter more?
8. Why is the penalty method used for alternatives instead of Yen's k-shortest paths? What does "[HEURISTIC]" promise and not promise?
9. Why does readiness ignore Redis but `/actuator/health` doesn't?
10. Our p95 is 19 ms with one client. What would you expect with 50 concurrent clients, and how would you measure it properly?
