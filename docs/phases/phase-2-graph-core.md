# Phase 2: Graph core

## STEP 1: What we built

The `algorithms` module now contains the graph engine that every routing feature will use. It is plain Java 21 with no Spring, no database and no I/O.

| Class | Purpose |
|---|---|
| `Graph`, `GraphNode`, `Edge` | Immutable directed road graph (adjacency list) |
| `EdgeWeight` | What "cost" means: `DISTANCE`, `TRAVEL_TIME`, `HOPS` |
| `BreadthFirstSearch` | Fewest-hops path; neighbourhood within k hops |
| `DepthFirstSearch` | Iterative reachability |
| `StronglyConnectedComponents` | Kosaraju SCC, used to validate one-way street networks |
| `Dijkstra` | Point-to-point, full tree, and one-to-many with early stop |
| `AStar`, `Heuristic`, `Heuristics`, `GeoMath` | Goal-directed search with haversine-based heuristics |
| `generator.CityGraphGenerator` | Deterministic synthetic city for tests and benchmarks (**synthetic data**, not a real map) |

## STEP 2: Architecture

```
algorithms (no Spring)                         app (later phases)
┌────────────────────────────────────┐        ┌──────────────────────┐
│ Graph ◄── built by Graph.Builder    │        │ RouteEngine (Phase 6)│
│   │                                 │ ◄───── │ Assignment (Phase 7) │
│   ├─ BFS / DFS / SCC                │        │ Optimizer  (Phase 8) │
│   ├─ Dijkstra ─┐ share QueueEntry,  │        └──────────────────────┘
│   └─ A* ───────┘ PathReconstruction │
└────────────────────────────────────┘
```

Two design rules:
1. **The graph is immutable.** Many API threads will search the same graph at once. With no mutation there are no locks and no race conditions. A traffic update (Phase 10) builds a new graph and swaps the reference.
2. **Weight is a parameter, not a property of the algorithm.** `Dijkstra.shortestPath(graph, s, t, EdgeWeight.DISTANCE)` is the shortest route; the same call with `TRAVEL_TIME` is the fastest route. Traffic later becomes another `EdgeWeight` (time × multiplier), with no change to Dijkstra.

## STEP 3: The DSA

### 3.1 Graph representation: adjacency list

| Option | Memory | "Neighbours of u" | Fits roads? |
|---|---|---|---|
| Adjacency matrix | O(V²) | O(V) | No. 10,000 nodes means 10⁸ cells, nearly all empty |
| Edge list | O(E) | O(E) | No. Every search step would scan all edges |
| **Adjacency list** (chosen) | O(V + E) | O(degree) | Yes. Roads are sparse; the generated 10,000-node city has 35,682 edges (≈3.6 per node) |
| CSR arrays (compressed) | O(V + E), cache-friendly | O(degree) | Faster, but harder to read. A later optimization if profiling shows it matters |

Edges are directed. A two-way street is two edges, because one-way streets exist and the two directions can have different traffic. Parallel edges are allowed (two different roads between the same junctions); Dijkstra simply picks the cheaper one.

### 3.2 BFS: where it fits and where it doesn't
BFS explores in rings of 1 hop, 2 hops, and so on, so the first time it reaches the target it has used the fewest edges. It **ignores edge weights**: in the test graph, BFS picks `0→1→3` (2 hops, 5 m) while Dijkstra picks `0→2→4→3` (3 hops, 3 m). So BFS is used for hop-count questions (fewest junctions, the k-hop neighbourhood) and never for "shortest route".

- Time O(V + E), space O(V).
- The queue is an `int[]` of size V. Each node is enqueued once (marked visited when enqueued), so V slots are enough, and there is no boxing.

### 3.3 DFS and strongly connected components (Kosaraju)
**Problem:** with one-way streets, node B can be reachable from A while A is not reachable from B. If a drop location sits on a dead end, the driver can get there but can't get back out.

**SCC:** a maximal set of nodes where every node reaches every other. Kosaraju's algorithm:
1. Run DFS on the graph and record nodes by finishing time.
2. Run DFS on the **reversed** graph, starting from the latest-finishing unvisited node. Each DFS tree is one SCC.

Why it works, in one sentence: the node that finishes last is in a "source" component, and in the reversed graph that component can't leak into any other component, so each second-pass DFS stays inside exactly one SCC.

- Time O(V + E), space O(V + E) for the reversed graph.
- **Iterative, not recursive.** A recursive DFS on a 200,000-node path needs 200,000 stack frames and throws `StackOverflowError`. The test `handlesVeryLongPathsWithoutStackOverflow` proves the iterative version works. The finishing order needs a stack of (node, next-edge-index) pairs to emulate "return from child".
- Alternative: Tarjan's single-pass algorithm. It has the same complexity and needs no reversed graph, but is harder to explain.
- On the 10,000-node synthetic city: 3 components, the largest holding 9,998 nodes. Phase 3 uses this to restrict routing to the largest SCC.

### 3.4 Dijkstra
**Invariant:** when a node is removed from the priority queue for the first time, its cost is final. This relies on non-negative weights, which is why `Edge` rejects negative, NaN and infinite values, and why Dijkstra re-checks custom weight functions.

**Lazy deletion:** Java's `PriorityQueue` has no decrease-key operation. When we find a cheaper cost for v, we push a new entry and skip outdated ones when they're polled (`if (settled[u]) continue`).

**Complexity of this implementation:**
- Each edge relaxation can push once, giving at most E pushes and E polls, each O(log E).
- Since E ≤ V², log E ≤ 2 log V. Total **O((V + E) log V)** time.
- **Space O(V + E):** the heap can hold up to E entries because of lazy deletion. An indexed heap with decrease-key keeps it to O(V), at the cost of more code.

**Three entry points:**
1. `shortestPath(s, t)` stops as soon as t is settled.
2. `shortestPathTree(s)` computes costs to every node.
3. `shortestPathTree(s, targets)` is **one-to-many with early stop**. Phase 7 uses it on the *reversed* graph from a pickup: one run gives every candidate driver's ETA to that pickup, instead of one Dijkstra run per driver. The test `reversedGraphGivesCostsTowardTheSource` proves the reversal trick.

**Determinism:** ties in the heap are broken by node id (`QueueEntry.ORDER`), so equal-cost routes always resolve the same way. That matters for caching and reproducible tests.

### 3.5 A*
A* orders the heap by `g(n) + h(n)`: the cost so far plus an estimate of the remaining cost. If h never overestimates and is **consistent** (`h(u) ≤ w(u,v) + h(v)`), A* returns optimal paths with the same settled-set logic as Dijkstra.

| Mode | Heuristic | Why it's safe |
|---|---|---|
| Distance | Haversine straight line to the target | No road is shorter than the straight line. The generator guarantees edge length ≥ straight line, and a test checks consistency on every edge |
| Time | Straight line ÷ **fastest speed in the graph** | Even driving the whole straight line at top speed can't beat that time |

**Measured, not assumed.** These are search-effort counts from a real run on the 100×100 synthetic city (10,000 nodes, 35,682 edges, seed 42). Wall-clock benchmarks come with JMH in Phase 3.

| Query | Dijkstra nodes settled | A* nodes settled |
|---|---|---|
| Short trip, distance mode | 737 | 130 |
| Short trip, time mode | 680 | 113 |
| Corner to opposite corner, distance mode | 10,000 | 9,461 |
| Corner to opposite corner, time mode | 10,000 | 9,996 |

**What this shows (honestly):**
- A* helps a lot when the target is near relative to the graph: about 5–6× fewer nodes settled here.
- It barely helps corner to corner, because the "ellipse" A* explores covers the whole grid anyway.
- The time heuristic is weaker than the distance heuristic: dividing by the *fastest* speed (arterials at 40 km/h) underestimates time on slow local streets.

In every one of the 600 random pairs tested, A* and Dijkstra returned the same cost.

## STEP 4: Contracts

```java
Path        Dijkstra.shortestPath(Graph g, int source, int target, EdgeWeight w)
ShortestPathTree Dijkstra.shortestPathTree(Graph g, int source, EdgeWeight w [, Collection<Integer> targets])
Path        AStar.shortestPath(Graph g, int source, int target, EdgeWeight w, Heuristic h)
Path        BreadthFirstSearch.fewestHops(Graph g, int source, int target)
List<Integer> BreadthFirstSearch.withinHops(Graph g, int source, int maxHops)
StronglyConnectedComponents StronglyConnectedComponents.of(Graph g)
```

`Path` contains node ids, the actual edges used (needed because of parallel edges), the cost, and `nodesSettled` (search effort). An unreachable target returns `Path.unreachable(...)` with `isFound() == false` and cost `+∞`, never `null`. Invalid input (unknown node ids, negative weights, bad coordinates) throws `IllegalArgumentException` immediately.

## STEP 6: Tests (57, all passing)

| Required edge case | Test |
|---|---|
| Empty graph | `GraphTest.emptyGraphHasNoNodesOrEdges`, `StronglyConnectedComponentsTest.emptyGraphHasNoComponents`, Dijkstra on an empty graph rejects the node |
| Single node | BFS/SCC single-node tests |
| Disconnected graph | `DijkstraTest.disconnectedGraph` |
| Unreachable destination | BFS, Dijkstra and A* `unreachable*` tests |
| Duplicate (parallel) edges | `GraphTest.parallelEdgesAreKept`, `DijkstraTest.picksCheaperOfParallelEdges` |
| Cycles and self-loops | `BreadthFirstSearchTest.terminatesOnCycles`, `DijkstraTest.handlesCyclesAndSelfLoops` |
| Large graph | 200,000-node ring SCC; 3,600-node city with 300 random pairs per mode |
| Invalid input | negative/NaN/infinite weights, unknown nodes, bad coordinates, negative hop limit |
| Equal-cost routes | `DijkstraTest.equalCostRoutesAreResolvedDeterministically` |
| Correctness of A* | `AStarTest.matchesDijkstraCostOnManyRandomPairs` (distance and time) |

## STEP 7: Review notes
- `Dijkstra.shortestPathTree(..., List.of())` (no targets) originally fell through to a full search. Fixed to stop immediately after the source, and covered by a test.
- Complexity claims were checked against the code: the heap holds up to E entries (lazy deletion), so space is O(V + E), not O(V).

## Interview questions (answer in the thread; I'll evaluate)
1. Why is BFS wrong for the shortest *route* in a road network, but fine for "fewest junctions"? Give an example graph.
2. Dijkstra breaks with negative edge weights. Show a 3-node graph where it returns the wrong answer.
3. Java's `PriorityQueue` has no decrease-key. How does this implementation get around it, and what does that do to space complexity?
4. What makes an A* heuristic admissible? What makes it consistent? Why does this implementation need consistency, not just admissibility?
5. Why does the travel-time heuristic divide by the *maximum* speed? What happens to A* performance if one edge in the city is a 120 km/h expressway?
6. Our measurement shows A* settling 9,461 of 10,000 nodes corner to corner, but only 130 vs 737 on a short trip. Explain why.
7. How do you get the ETA from 100 drivers to one pickup with a single Dijkstra run?
8. Why is the DFS iterative? What would happen with recursion on a 200,000-node path?
9. Explain Kosaraju's algorithm. Why does the second pass run on the reversed graph?
10. Why is `Graph` immutable? What would go wrong if traffic updates mutated edge weights in place while requests were being served?
