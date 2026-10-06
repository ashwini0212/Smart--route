# Phase 3: Road network data, snapping and benchmarks

## STEP 1: What we built

| Piece | Purpose |
|---|---|
| `scripts/osm/osm_roads.py` (+10 unit tests) | Downloads drivable roads for a bounding box from OpenStreetMap (Overpass API) and writes `nodes.csv` / `edges.csv` |
| `graph.io.RoadNetworkCsv` | Streams that CSV format into a `Graph` (and writes it back); malformed rows fail with file:line |
| `graph.LargestComponent` | Keeps only the largest strongly connected component and renumbers nodes |
| `spatial.NearestNodeIndex` | 2-d tree: snaps a GPS point to the nearest road node |
| `backend/benchmarks` (JMH) | Dijkstra vs A*, k-d tree vs linear scan, one-to-many vs N runs |

### What is *not* done, and why
The real Bengaluru extract is **not committed**. This project's cloud build environment blocks `overpass-api.de` (HTTP 403 from the egress proxy), so I could not download it. The script is tested against a hand-made Overpass response. Running one command on any machine with internet access produces the files; see [data/road-network/README.md](../../data/road-network/README.md). Until then the app uses the deterministic **synthetic** city, labelled as synthetic.

## STEP 2: Architecture: from raw map to routable graph

```
OSM (Overpass JSON)
   │  osm_roads.py: keep drivable highway classes, split ways into segments,
   │                one-way handling (oneway=yes / -1 / roundabout), speed by class or maxspeed
   ▼
nodes.csv + edges.csv  (versioned in data/, ODbL attribution)
   │  RoadNetworkCsv.read()            O(V + E), streaming
   ▼
Graph ──► LargestComponent.of()        O(V + E), drops dead ends / islands, logs how many
   │
   ├──► NearestNodeIndex.of()          O(V log² V) build, O(log V) average query
   └──► Dijkstra / A*                  (Phase 6 RouteEngine)
```

Why the graph lives in **files**, not PostgreSQL tables: routing never queries edges with SQL; the whole graph is loaded into memory once. A versioned CSV dataset is simpler to diff, review and reproduce than 100k table rows, and PostgreSQL keeps what really is relational: orders, drivers, assignments. Recorded as ED-14.

## STEP 3: The DSA

### 3.1 Largest strongly connected component
OSM extracts always contain fragments: a one-way service road whose exit was outside the bounding box, a gated lane, a mistagged segment. A drop location snapped onto such a fragment would be "unreachable" at request time. Running Kosaraju once at load time and keeping only the largest SCC guarantees every snapped pair has a route in both directions, and turns the problem into a logged data-quality number.

### 3.2 2-d tree for nearest-node snapping
- **Problem:** "Which intersection is closest to (12.9716, 77.5946)?" A linear scan is O(V) per lookup, and snapping happens for every order, every driver ping and every route request.
- **Structure:** Points are projected to a flat x/y plane in metres. The equirectangular projection around the mean latitude is accurate to centimetres at city scale; the test checks agreement with brute-force haversine within 0.5 m over 2,000 random queries. The tree splits on x at even depths and y at odd depths, always at the median, so it is balanced.
- **Query:** Descend to the region holding the point, then backtrack. The other half of a split is visited **only if the splitting line is closer than the best distance found so far**. That pruning is the whole speed-up.
- **Complexity:** build O(n log² n) (a sort per level; a median-of-medians build would be O(n log n)); query O(log n) average, O(n) worst case for adversarial layouts; space O(n).
- **Alternatives:** a uniform grid (simpler, but degrades with dense clusters like a city centre); a quadtree; Redis GEO / PostGIS (used for driver positions later, but routing needs the graph's own node ids in memory).

## STEP 6: Benchmarks (real JMH results)

Environment: cloud VM, 4 vCPU Intel Xeon @ 2.8 GHz, 15 GB RAM, OpenJDK 21.0.12, JMH 1.37, 1 fork, 3 × 1 s warm-up, 5 × 1 s measurement. Cities are synthetic (seed 42), restricted to their largest SCC. Raw output: [docs/benchmarks/phase-3-jmh.json](../benchmarks/phase-3-jmh.json). Reproduce with:

```bash
cd backend && ./mvnw -pl benchmarks -am package -DskipTests && java -jar benchmarks/target/benchmarks.jar
```

> The ± values are JMH's 99.9 % confidence intervals and some are wide (up to ±40 %). This is a shared cloud VM, so treat the numbers as ratios and orders of magnitude, not precise timings.

### Dijkstra vs A* (mean µs per query)

| City (nodes ≈) | Trip | Weight | Dijkstra | A* | A* speed-up |
|---|---|---|---|---|---|
| 50×50 (2.5k) | local | distance | 48.0 | 27.8 | 1.7× |
| 50×50 | cross-city | distance | 317 | 317 | 1.0× |
| 100×100 (10k) | local | distance | 86.5 | 55.6 | 1.6× |
| 100×100 | local | time | 88.6 | 65.2 | 1.4× |
| 100×100 | cross-city | distance | 1,818 | 1,157 | 1.6× |
| 100×100 | cross-city | time | 1,670 | 1,394 | 1.2× |
| 200×200 (40k) | local | distance | 227 | 160 | 1.4× |
| 200×200 | cross-city | distance | 12,185 | 6,591 | 1.8× |
| 200×200 | cross-city | time | 12,039 | 6,706 | 1.8× |

"Local" = target within 15 road segments of the source; "cross-city" = uniformly random pair.

**What the numbers say:**
1. **A* is never slower and is up to ~1.8× faster.** Its advantage grows with city size on long trips, because Dijkstra's search disc grows with the square of the distance while A*'s ellipse is narrower.
2. **For local trips the speed-up (1.4–1.7×) is much smaller than the node-count reduction** measured in Phase 2 (about 5–6× fewer nodes settled). The reason is in our code: every query allocates and fills three arrays of size V (`cost`, `parentEdge`, `settled`). For a short trip on a 40k-node graph, that O(V) setup costs as much as the search itself. **Optimization noted for later (not done):** reuse per-thread arrays with a "visited generation" stamp, or use hash maps for small searches. Benchmarks first, then optimize only if routing becomes a bottleneck.
3. **The time heuristic gains less than the distance heuristic** (1.2× vs 1.6× cross-city at 10k), as predicted: dividing by the fastest speed makes the estimate loose on slow streets.
4. Absolute latency: **~0.06 ms for a local route and ~1–2 ms cross-city on a 10k-node city.** That is far below the Phase 0 target of p95 < 50 ms. Those are averages from a microbenchmark, not p95 under API load; Phase 13 measures the full request path.

### Nearest-node snapping (mean µs per lookup)

| City | Linear scan | k-d tree | Speed-up |
|---|---|---|---|
| 100×100 (10k nodes) | 1,194 | 0.63 | ~1,900× |
| 200×200 (40k nodes) | 6,039 | 0.71 | ~8,500× |

The linear scan grows linearly with nodes (5× more time for 4× nodes; haversine per node is expensive). The tree barely changes, as expected for O(log n).

### ETA from N drivers to one pickup (mean ms)

| Drivers | N separate Dijkstra runs | 1 run on reversed graph | Speed-up |
|---|---|---|---|
| 10 | 19.0 | 3.8 | 5× |
| 100 | 250 | 4.5 | 55× |

One run on the reversed graph costs about the same no matter how many drivers there are, because it stops once the farthest of them is settled. This is the measured justification for the assignment-engine design in Phase 7.

## Tests
- Java: 71 algorithm tests (14 new: CSV parsing/round trip/error reporting, largest-SCC restriction and full mutual reachability, k-d tree vs brute force on 2,000 queries, empty/single/duplicate-point cases).
- Python: 10 tests for the OSM converter (two-way, one-way, reversed one-way, roundabout, footway excluded, missing nodes, maxspeed parsing incl. mph, CSV header). CI runs them in a new `scripts` job.

## Interview questions
1. Why restrict routing to the largest strongly connected component instead of the largest *weakly* connected one?
2. How does a k-d tree decide it can skip the other half of a split? What is the worst case for a k-d tree?
3. Why is a flat projection acceptable for snapping inside a city but not for a whole country?
4. Our A* settles ~5× fewer nodes than Dijkstra on short trips but is only ~1.5× faster. Explain the gap and how you'd close it.
5. Why does one Dijkstra run on the reversed graph give ETAs *to* the pickup from every driver?
6. What can make JMH numbers misleading, and what did we do about it?
7. Why store the road network as versioned CSV files instead of PostgreSQL tables?
