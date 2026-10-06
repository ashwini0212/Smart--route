# Engineering decisions

Each entry: **Decision**, **Reason**, **Alternative**, **Tradeoff**. New entries are added in the phase where the decision is made. The full technology comparison is in [phase-0-plan.md §8](phase-0-plan.md#8-technology-justification).

## ED-1 Modular monolith (Phase 0)
- **Decision:** One Spring Boot deployable with modules that have explicit public APIs.
- **Reason:** One build, one database, simple local development and testing, while module boundaries still show service design.
- **Alternative:** Microservices per domain.
- **Tradeoff:** Modules can't be scaled or deployed independently. Acceptable at this size; extraction path documented later.

## ED-2 Algorithms in a separate Maven module with no Spring dependency (Phase 1)
- **Decision:** `backend/algorithms` is its own module; `app` depends on it, never the reverse.
- **Reason:** The compiler enforces that algorithms are framework-free: a Spring import there does not compile. Tests run in milliseconds, and benchmarks can live next to the code.
- **Alternative:** A package `com.smartroute.algorithms` inside the app, or a top-level `algorithms/` folder.
- **Tradeoff:** Slightly more build configuration (parent POM + modules).

## ED-3 Java 21 and Spring Boot 4.1 (Phase 1)
- **Decision:** Target Java 21 (LTS) and Spring Boot 4.1.1, the current stable release.
- **Reason:** Starting a new project on the previous major version means a migration soon after. Java 21 adds records/pattern matching improvements and virtual threads; Boot 4 requires Java 17+ so the "Java 17+" requirement is met.
- **Alternative:** Spring Boot 3.5 on Java 17, which has more tutorials online.
- **Tradeoff:** Some online examples use Boot 3 package names (for example test auto-configuration moved to `org.springframework.boot.webmvc.test.autoconfigure`). We note such differences when they come up.

## ED-4 Add dependencies only in the phase that uses them (Phase 1)
- **Decision:** Phase 1 includes only web + actuator. JPA/Flyway, Security, Redis and Kafka starters arrive in Phases 4, 5, 6 and 9.
- **Reason:** Every dependency must have a reason. Adding JPA now would force a database connection for a test that does not need one.
- **Alternative:** Add the whole stack up front.
- **Tradeoff:** The `pom.xml` changes in several phases, which is visible and reviewable in Git history.

## ED-5 Docker Compose with health-checked startup order (Phase 1)
- **Decision:** `depends_on: condition: service_healthy` for every dependency; each service has a health check.
- **Reason:** `depends_on` alone only waits for a container to *start*, not to be *ready*. A backend that starts before PostgreSQL accepts connections crashes.
- **Alternative:** Retry loops/wait scripts in the application entrypoint.
- **Tradeoff:** Slower `docker compose up` (Kafka needs ~15–30 s to become healthy). Kubernetes would use readiness probes instead; the backend already exposes `/actuator/health/readiness`.

## ED-6 Kafka in KRaft mode, single node, topics not auto-created (Phase 1)
- **Decision:** `apache/kafka` image in KRaft mode; `auto.create.topics.enable=false`.
- **Reason:** KRaft removes ZooKeeper (one container less; ZooKeeper support was removed in Kafka 4). Explicit topic creation means partition counts are a deliberate design decision (Phase 9), not an accident of the first producer.
- **Alternative:** Confluent images with ZooKeeper; auto-created topics.
- **Tradeoff:** Single broker, replication factor 1: no fault tolerance. Fine for development only.

## ED-7 Same-origin API access through a proxy (Phase 1)
- **Decision:** The browser only talks to the frontend origin. Vite's dev server (development) and nginx (Docker) forward `/api` and `/actuator/health` to the backend.
- **Reason:** No CORS configuration needed for our own UI, cookies/tokens stay same-origin, and dev and Docker behave the same way.
- **Alternative:** Frontend calls `http://localhost:8080` directly with CORS enabled.
- **Tradeoff:** One more hop. CORS is still configured explicitly in Phase 5 for other clients.

## ED-8 Minimal public actuator surface (Phase 1)
- **Decision:** Only `health` and `info` are exposed; health shows no component details; stack traces and exception messages are never included in error responses.
- **Reason:** Endpoints like `/actuator/env` leak configuration and secrets. Tested in `SmartRouteApplicationTests`.
- **Alternative:** Expose everything in development.
- **Tradeoff:** Metrics need explicit, authenticated exposure (Phase 12).

## ED-9 Non-root, layered backend image (Phase 1)
- **Decision:** Multi-stage build; runtime image is a JRE on Alpine, runs as user `smartroute`, and the jar is extracted into Spring Boot layers.
- **Reason:** Smaller attack surface; dependencies (which rarely change) sit in a lower Docker layer than application classes, so rebuilds after a code change are fast.
- **Alternative:** Single-stage JDK image running `java -jar` as root.
- **Tradeoff:** A more complex Dockerfile.

## ED-10 Adjacency list over matrix or CSR (Phase 2)
- **Decision:** `List<List<Edge>>` adjacency list, immutable after build.
- **Reason:** Road graphs are sparse (≈3.6 edges per node in our synthetic city). O(V + E) memory, O(degree) neighbour iteration, readable code.
- **Alternative:** Adjacency matrix (O(V²) memory); CSR primitive arrays (faster, cache-friendly).
- **Tradeoff:** Object-per-edge costs more memory and cache misses than CSR. Revisit only if profiling shows routing is memory-bound.

## ED-11 Dijkstra with lazy deletion on java.util.PriorityQueue (Phase 2)
- **Decision:** Push duplicate entries and skip stale ones instead of decrease-key.
- **Reason:** Simple, correct, uses the standard library.
- **Alternative:** Indexed binary heap with decrease-key (heap stays O(V)); Fibonacci heap (better asymptotics, worse constants).
- **Tradeoff:** Heap can grow to O(E) entries.

## ED-12 A* requires a consistent heuristic (Phase 2)
- **Decision:** Haversine distance (distance mode) and haversine ÷ max graph speed (time mode); both consistent.
- **Reason:** With a consistent heuristic, the settled-set optimization stays correct and A* returns the same cost as Dijkstra (tested on 600 random pairs).
- **Alternative:** Inflated (weighted) A*: faster but not optimal. ALT landmarks: tighter bounds, needs preprocessing.
- **Tradeoff:** The time heuristic is weak when speeds vary a lot, so A* gains less in time mode (measured in Phase 2 doc).

## ED-13 Iterative DFS (Phase 2)
- **Decision:** All DFS code uses explicit stacks.
- **Reason:** Recursive DFS overflows the JVM stack on long paths (tested with 200,000 nodes).
- **Tradeoff:** The finishing-order DFS is less obvious to read than the recursive version.

## ED-14 Road network as versioned CSV files, not database tables (Phase 3)
- **Decision:** The graph is loaded from `data/road-network/<dataset>/{nodes,edges}.csv` into memory.
- **Reason:** Routing never queries edges with SQL; the graph is read once at startup. Files are easy to diff, review and reproduce. PostgreSQL keeps relational, frequently changing data.
- **Alternative:** `road_node`/`road_edge` tables (as sketched in Phase 0); PostGIS + pgRouting.
- **Tradeoff:** Changing the road network needs a new dataset version and a restart (or reload), not an UPDATE.

## ED-15 Keep only the largest strongly connected component (Phase 3)
- **Decision:** After loading, drop every node outside the largest SCC.
- **Reason:** Guarantees a route both ways between any two snapped points; turns "unreachable" into a load-time data-quality report.
- **Alternative:** Keep everything and return "unreachable" at request time.
- **Tradeoff:** A few real but badly connected streets are removed; addresses there snap to the nearest kept node.

## ED-16 2-d tree for nearest-node snapping (Phase 3)
- **Decision:** Balanced 2-d tree over projected node coordinates.
- **Reason:** Measured ~0.7 µs per lookup vs ~6 ms for a linear scan on 40k nodes.
- **Alternative:** Uniform grid buckets, quadtree, PostGIS KNN query.
- **Tradeoff:** O(n) worst case on adversarial layouts; rebuild needed when the graph changes.
