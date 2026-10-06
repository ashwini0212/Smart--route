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

## ED-17 Modules reference each other by id, not JPA relationships (Phase 4)
- **Decision:** `DeliveryOrder.warehouseId` is a `Long`; other modules are reached through their service (`WarehouseService.requireActive`). Repositories are package-private (ArchUnit test).
- **Reason:** No lazy-loading surprises or hidden N+1 queries; each module owns its tables and could be split out later.
- **Alternative:** `@ManyToOne` associations across modules.
- **Tradeoff:** Joins across modules are explicit service calls or dedicated read queries, which is more code.

## ED-18 Readable codes from database sequences (Phase 4)
- **Decision:** `DRV-000001` / `ORD-000001` come from PostgreSQL sequences.
- **Reason:** Unique under concurrency without locks; `max + 1` races.
- **Alternative:** UUIDs only; a counter table with row locks.
- **Tradeoff:** Gaps appear when a transaction rolls back. Codes are identifiers, not counts, so that is fine.

## ED-19 Optimistic locking on every entity (Phase 4)
- **Decision:** `@Version` column in `BaseEntity`; a lost update returns `409 CONCURRENT_MODIFICATION`.
- **Reason:** Conflicts on the same order are rare, and a version check costs nothing until one happens.
- **Alternative:** `SELECT ... FOR UPDATE` everywhere.
- **Tradeoff:** The losing client must retry. Phase 7 uses row locks where a conflict is expected (reserving driver capacity).

## ED-20 Business rules as database CHECK constraints too (Phase 4)
- **Decision:** Ranges, enums and the time-window rule are CHECK constraints in the migration, in addition to Bean Validation.
- **Reason:** The API gives readable errors; the database guarantees no code path or manual SQL can store an impossible row.
- **Tradeoff:** Adding an enum value needs a migration.

## ED-21 Testcontainers PostgreSQL instead of H2 (Phase 4)
- **Decision:** Integration tests use a real PostgreSQL 17 container.
- **Reason:** Partial indexes, CHECK constraints, sequences and Flyway SQL behave exactly as in production.
- **Alternative:** H2 in PostgreSQL mode.
- **Tradeoff:** Tests need Docker and start in seconds instead of milliseconds; one container is shared by all tests.


## ED-22 Short JWT access token + opaque rotating refresh token (Phase 5)
- **Decision:** 15-minute HS256 access token (Spring Security resource server, Nimbus); 7-day random refresh token stored as a SHA-256 hash, rotated on every use, with family revocation on reuse.
- **Reason:** API requests need no database lookup; logins can still be revoked (refresh tokens live in the database).
- **Alternative:** Server sessions (simpler revocation, but sticky sessions or a shared store); long-lived JWTs (no revocation).
- **Tradeoff:** A disabled user or changed role keeps their current access token for up to 15 minutes. Two tabs refreshing at the same instant can trip reuse detection and log the user out.

## ED-23 Refresh token in an HttpOnly SameSite=Strict cookie, access token in memory (Phase 5)
- **Decision:** The refresh token never appears in a response body; it is a cookie scoped to `/api/auth`. The access token is returned in JSON and kept in memory by the frontend.
- **Reason:** XSS can't read an HttpOnly cookie, and SameSite=Strict stops cross-site requests from sending it, so CSRF tokens aren't needed.
- **Alternative:** Both tokens in localStorage (readable by any injected script).
- **Tradeoff:** Non-browser clients must handle cookies for refresh. A page reload needs one refresh call.

## ED-24 HS256 with one shared secret (Phase 5)
- **Decision:** Symmetric HMAC signing with `JWT_SECRET` (≥ 32 bytes, checked at startup).
- **Reason:** One service issues and verifies its own tokens.
- **Alternative:** RS256/ES256 key pair with a JWKS endpoint.
- **Tradeoff:** If other services ever verify tokens they would need the signing secret too; then switch to an asymmetric key pair.

## ED-25 Authorization in two layers, guarded by a build rule (Phase 5)
- **Decision:** URL rules for public/authenticated/admin areas plus `@PreAuthorize` on each controller method; an ArchUnit rule fails the build if a write endpoint has none.
- **Reason:** Ownership checks ("a driver sees only their own record") belong next to the endpoint; the build rule stops a new endpoint from silently being open to every logged-in user.
- **Alternative:** Only URL patterns in one config class.
- **Tradeoff:** Role lists are spread over controllers (kept consistent via constants in `Access`).

## ED-26 In-memory token-bucket login limiter (Phase 5)
- **Decision:** 5 attempts per minute per email and 20 per client IP, in process memory. Client IPs come from `X-Forwarded-For` only when the peer is a private-network proxy (Tomcat RemoteIpValve), and nginx overwrites that header.
- **Reason:** Slows password guessing with no extra infrastructure; the higher IP limit avoids locking out an office behind one NAT address.
- **Alternative:** Redis-backed limiter (correct across several app instances); account lockout (lets an attacker lock out real users).
- **Tradeoff:** With N app instances the effective limit is N times higher. Moving to Redis is a new `RateLimiter` implementation.

## ED-27 Immutable network snapshots swapped through an AtomicReference (Phase 6)
- **Decision:** Traffic changes build a complete new graph (copy-on-write, O(V + E)) and publish it with one reference swap; each request reads the reference once.
- **Reason:** Route searches never lock and never see a half-applied update.
- **Alternative:** Mutable edge weights behind a read-write lock.
- **Tradeoff:** Each update copies the whole graph (milliseconds for a city; too slow for per-second updates on a country-sized graph).

## ED-28 Cache keys from snapped nodes plus a content fingerprint; expiry instead of invalidation (Phase 6)
- **Decision:** `route:<fingerprint>:<mode>:<fromNode>:<toNode>`, TTL 10 minutes. The fingerprint is a hash of the dataset and the traffic map.
- **Reason:** Nearby requests share entries; a traffic change produces a new fingerprint, so stale routes are never read and nothing has to be deleted. Instances with the same data share entries.
- **Alternative:** Raw coordinates as keys (almost no hits); deleting keys on every traffic change.
- **Tradeoff:** Old entries occupy Redis memory until their TTL ends.

## ED-29 Redis is optional, with a 30-second back-off (Phase 6)
- **Decision:** Cache errors count as misses; after an error the cache is bypassed for 30 s. Readiness ignores Redis.
- **Reason:** Measured 0.52 s per request with Redis down before the back-off (two connection timeouts); ~35 ms after.
- **Alternative:** Resilience4j circuit breaker (more features, another dependency).
- **Tradeoff:** For 30 s after Redis returns, requests still skip the cache.

## ED-30 Alternative routes by the penalty method, labelled heuristic (Phase 6)
- **Decision:** Penalize edges of found routes and search again; accept routes ≤ 1.4× the optimum with ≤ 70 % overlap.
- **Reason:** Gives genuinely different roads; Yen's k-shortest paths gives near-duplicates on a grid.
- **Tradeoff:** No guarantee of the best k or the most diverse routes; parameters are tuned by eye on the synthetic city.

## ED-31 Route history as one row with a coordinate array (Phase 6)
- **Decision:** `route_record.path DOUBLE PRECISION[]` (flattened lat/lon) instead of a segments table.
- **Reason:** A route is always read whole; one row per route keeps writes and reads to one statement.
- **Alternative:** `route_segment` rows; PostGIS LINESTRING.
- **Tradeoff:** No SQL queries over individual segments (not needed yet). The table grows with every request; a retention policy is future work.
