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

## ED-32 Capacity is reserved under a pessimistic row lock, in a fixed lock order (Phase 7)
- **Decision:** Assignment locks the order row (`SELECT ... FOR UPDATE`), then the driver row, re-checks every rule on the locked data, then writes. Always that order, never the reverse. The candidate ranking runs *before* that transaction, so the locked read is the first time the transaction sees the driver.
- **Reason:** Two dispatchers assigning at the same instant must not put a driver over capacity or give one order to two drivers. `@Version` alone would detect it, but only as a late "concurrent modification" conflict after the work was done, and it cannot express "check the current load before deciding". `AssignmentConcurrencyTest` proves it: with the locks removed, the same tests fail with `ObjectOptimisticLockingFailureException` instead of a clear 422.
- **Alternative:** Optimistic retries; one serialized dispatcher thread; `SERIALIZABLE` isolation.
- **Tradeoff:** Concurrent assignments to the same driver serialize, and a lock is held for the length of the transaction. The fixed lock order is what prevents deadlock, so it is a rule future code must follow.

## ED-33 Weighted score for driver choice, labelled heuristic (Phase 7)
- **Decision:** score = (w_eta·priority × ETA term + w_workload × workload term + w_capacity × capacity-fit term) / Σ weights, each term in [0, 1]; weights live in `assignment_config` and an admin can change them at runtime.
- **Reason:** "Nearest driver" ignores that a driver already carrying seven parcels is a bad choice, and that a 500 kg order should prefer the van it nearly fills over an empty truck. Measured on the seeded data (100 orders, fleet not saturated): the configured weights spread the work over 52 drivers instead of 30, standard deviation of deliveries per driver 1.13 vs 1.95 and busiest driver 4 vs 8, at the cost of 36 s more mean pickup ETA (2.7 vs 2.1 min). With 400 orders, where the fleet saturates, the difference nearly disappears (stdev 2.69 vs 2.79).
- **Alternative:** A single objective (ETA); an optimal matching (Hungarian algorithm, O(n³)) over all waiting orders.
- **Tradeoff:** It optimizes nothing provably. The weights are judgement, not a result; this is why the API labels the ranking `[HEURISTIC]` and returns the full score breakdown so a dispatcher can see why a driver was chosen.

## ED-34 Greedy auto-dispatch, one transaction per order (Phase 7)
- **Decision:** Waiting orders leave a priority queue (priority, then deadline, then age) and each takes the best available driver; the choice is never revisited. Each order is assigned in its own transaction, and if its top candidate was taken meanwhile the next two are tried.
- **Reason:** A dispatcher's run must not fail as a whole, and an order decided in milliseconds is worth more than a globally optimal matching computed over stale positions. One failure costs one order.
- **Alternative:** Hungarian algorithm over the whole batch (optimal for a fixed snapshot, O(n³), all-or-nothing); min-cost flow.
- **Tradeoff:** An urgent order can take a driver a later order needed more; the result depends on the processing order. Both are stated in the API response (`"algorithm": "greedy by priority queue [HEURISTIC]"`).

## ED-35 One reversed-graph Dijkstra for all driver ETAs (Phase 7)
- **Decision:** ETAs to a pickup come from a single Dijkstra on the reversed road graph, started at the pickup and stopped once every candidate node is settled. Each snapshot carries its reversed graph. The auto-dispatcher computes one full tree per warehouse and reuses it for every order of that warehouse.
- **Reason:** n searches become one. The costs are exact travel times with current traffic (Dijkstra, optimal), not straight-line estimates. The candidate endpoint measures p50 18 ms / p95 27 ms with 105 drivers on shift (baseline authenticated GET: 5 ms).
- **Alternative:** One Dijkstra or A* per driver; haversine distance as the ETA.
- **Tradeoff:** Each snapshot costs a second graph in memory (O(V + E)). One-way streets make the direction matter: searching the forward graph from the pickup would answer "time *from* the warehouse", which is not the same number.

## ED-36 Redis GEO as a pre-filter, with the database as the source of truth (Phase 7)
- **Decision:** Driver positions are mirrored into a Redis GEO set to answer "who is within R metres?"; the index is rebuilt at startup and whenever it may be stale, and every candidate's state is re-read from PostgreSQL.
- **Reason:** The radius query is the one step that would otherwise scan the whole driver table. Keeping the database authoritative means a Redis outage degrades speed, not correctness: measured with Redis stopped, the same 56 drivers in radius are found by a haversine scan (p50 20 ms after the first request pays the 250 ms timeout), and readiness stays UP.
- **Alternative:** PostGIS with a GiST index (one less moving part, but a new extension and a schema change); scanning every driver.
- **Tradeoff:** Two copies of position data. The index can lag by one commit; it is a pre-filter only, so a lag can at worst leave a driver out of one ranking.

## ED-37 Both an exact and a heuristic stop sequencer, with the threshold set by measurement (Phase 8)
- **Decision:** Held-Karp (exact, O(n²·2ⁿ)) up to 12 stops, nearest neighbour + 2-opt above it; the client can force either and ask for both to see the gap. Every response carries `optimal` and the algorithm name.
- **Reason:** Ordering stops is NP-hard, so there is no single right answer: small runs deserve a provably shortest order, big ones need an answer at all. The threshold is where the measurement put it, not where it felt right: exact costs 2.9 ms at 12 stops and 15 ms at 14, while the heuristic costs 0.004 ms and averages 2 % above optimal (worst case 19 %).
- **Alternative:** Only the heuristic (simpler, never provably right); only exact (fails above ~16 stops); an external solver (OR-Tools) — a large dependency and nothing learned.
- **Tradeoff:** Two code paths and two sets of tests. The heuristic's worst case is bad enough that a user must be told which one answered, which is why `optimal` is in the response rather than implied.

## ED-38 Stops are ordered by travel cost; time windows are reported, not planned around (Phase 8)
- **Decision:** Sequence by travel cost, then compute arrival times and list every stop that misses its deadline in `lateStops`. Capacity, by contrast, is enforced: exceeding it is a 422.
- **Reason:** A dispatcher needs to know which promise is at risk; silently reordering to save one window, or dropping a stop, hides the problem. Capacity is physical — a parcel either fits or does not — so it is an error, not a note.
- **Alternative:** Solving the vehicle-routing problem with time windows (a different, harder problem); penalizing late arrivals inside the cost function.
- **Tradeoff:** The returned order can be late when another order would not have been. The docs and the field name say so; this phase does not claim to solve VRPTW.

## ED-39 The optimize endpoint is rate-limited per user (Phase 8)
- **Decision:** 20 optimizations per minute per user, reusing the Phase 5 token bucket; over that, 429 with `Retry-After`.
- **Reason:** This is the one endpoint whose cost grows exponentially with input size: 20 stops at EXACT would be thousands of times a 10-stop request. A cap keeps one client from taking the CPU that serves everyone else.
- **Alternative:** A work queue with a thread budget; no limit; rejecting large inputs outright (already done at 20 stops and 16 for exact).
- **Tradeoff:** A legitimate bulk user hits the limit and must pace themselves; the limit is per instance, like the login limiter (ED-26).

## ED-40 A transactional outbox instead of publishing from the service (Phase 9)
- **Decision:** Domain events are written to `outbox_event` in the same transaction as the change they describe (`DomainEvents.append` is `Propagation.MANDATORY`). A scheduled relay publishes unpublished rows to Kafka oldest-first and stamps `published_at`.
- **Reason:** A send inside a transaction can succeed while the transaction rolls back (an event about something that never happened) or fail after it commits (a change nobody hears about); no ordering of the two calls fixes it, because they are two systems. One database write makes the change and the event one atomic fact.
- **Alternative:** Kafka transactions with the database in a 2PC (heavy, and PostgreSQL + Kafka 2PC is not a path worth taking for this); publishing after commit from a `@TransactionalEventListener` (loses the event if the send fails); change data capture with Debezium (a separate service to run).
- **Tradeoff:** Events are late by up to the polling interval — measured p50 474 ms at the default 500 ms, 59 ms at 50 ms — and the outbox table needs a retention job. Delivery becomes at-least-once, which pushes deduplication onto every consumer (ED-41).

## ED-41 Consumers deduplicate by inserting a marker, not by checking first (Phase 9)
- **Decision:** A consumer inserts `(consumer_group, event_id)` into `processed_event` in the same transaction as its work. A repeated delivery fails the primary key, the transaction rolls back, and `EventConsumers.handleOnce` reports a skipped duplicate (the exception has to cross the transaction boundary to be catchable).
- **Reason:** "Have I seen this?" followed by the work is two steps that two threads or two instances can interleave; four concurrent deliveries of one event are tested and exactly one does the work. The marker is per consumer group, so a consumer added later reads the whole log independently.
- **Alternative:** A `SELECT` before the work (racy); Kafka exactly-once semantics with read-process-write transactions (ties the consumer's side effects to Kafka, and the side effect here is a database write); making every consumer naturally idempotent (not possible in general).
- **Tradeoff:** One extra row and one extra insert per event per consumer group, and a table that grows with the stream (it needs the same kind of retention the outbox has). A failed delivery must leave no marker, which is why the claim and the work share one transaction rather than being committed separately.

## ED-42 Blocking retries with exponential back-off, then a dead-letter topic (Phase 9)
- **Decision:** Four attempts (200 ms, 400 ms, 800 ms) in the consumer, then publish to `<topic>.DLT` and move on. Parsing failures (unknown event type, a version newer than the consumer knows) are marked non-retryable and go straight to the dead-letter topic.
- **Reason:** Blocking keeps the partition's order while one record is retried, which matters because order events are keyed by order. Retrying is right for something temporarily unavailable; a malformed record will never succeed, so retrying it only delays every later event behind it.
- **Alternative:** Non-blocking retry topics (`@RetryableTopic`): higher throughput, but a retried record is re-delivered out of order; infinite retries (one bad record stops the partition); dropping failures (silent data loss).
- **Tradeoff:** A failure that lasts longer than ~1.4 s sends the record to the dead-letter topic, so someone has to look at that topic — there is no alerting on it yet. During the back-off, the partition is stalled for every other key as well.

## ED-43 Topics named after the lifecycle step, created by the application (Phase 9)
- **Decision:** Seven topics named after what happened (`smartroute.order-created`, `…delivery-completed`, …), each with 3 partitions and a 1-partition `.DLT`, declared as a `KafkaAdmin.NewTopics` bean. Brokers run with auto-creation off. Unassignment shares the assignment topic; `IN_TRANSIT` is not published at all.
- **Reason:** Consumers subscribe by what they care about rather than filtering a firehose. Creating the topics in code means a fresh cluster gets the intended partition count — auto-creation would use the broker default, and partitions can be added later but never removed.
- **Alternative:** One topic for everything with the type as a header (simplest, every consumer reads everything); a topic per aggregate (`order-events`), which couples unrelated consumers to one stream.
- **Tradeoff:** Seven topics plus seven dead-letter topics to operate, and a new event type usually means a new topic to create and subscribe to. Three partitions is a default chosen for headroom, not a measured number.

## ED-44 The live map reads a Redis read model fed by Kafka, not the driver table (Phase 10)
- **Decision:** `LivePositions` keeps each driver's latest position in Redis, written by a consumer of the location topic rather than by the API call that recorded the position. Reads fall back to the database, and `POST /api/tracking/positions/rebuild` refills the cache.
- **Reason:** The write path already has one job it must get right (the row and its outbox event in one transaction); adding a second store to it adds a second way to fail and a second thing to keep consistent. Feeding the cache from the stream makes it a derived view: it can be rebuilt, losing it loses nothing, and the same stream can feed other views later.
- **Alternative:** Writing Redis in the same service call (fast, but now two stores to keep in step and nothing to rebuild from); querying the driver table for every map refresh (correct, and a full scan per connected dashboard); keeping positions only in memory (lost on restart, wrong with more than one instance).
- **Tradeoff:** A position is visible on the map about half a second after it is recorded, because it travels through the outbox relay (ED-40). The fallback path means a Redis outage degrades to the last committed positions rather than an error, but those can be staler than the cache.

## ED-45 Positions are compared by timestamp before being stored (Phase 10)
- **Decision:** A stored position is only replaced by one whose own timestamp is later; an older event updates nothing and pushes no frame.
- **Reason:** The Kafka key is the driver id, so one driver's events stay in order within a partition — but a retry, a replay of the topic, or two producers can still deliver an older position after a newer one, and a map that jumps backwards is worse than one that lags. The guard makes the read model's correctness independent of delivery order.
- **Alternative:** Trusting the partition order (works until the first replay); a sequence number per driver (another thing to allocate and persist); storing a short history and taking the newest on read (more memory, more read work, for a value nobody asked for).
- **Tradeoff:** A driver whose clock is ahead can pin their own position until real time catches up, since the timestamp comes from the position rather than the server. Accepted here because positions are server-stamped on the API path; a device-stamped position would need the server's own time as well.

## ED-46 The live-feed consumer deliberately does not deduplicate (Phase 10)
- **Decision:** The `live-feed` consumer group has no `processed_event` claim, unlike the event log (ED-41).
- **Reason:** Its only side effects are a last-write-wins position and a frame in a browser. Handling an event twice stores the same position and sends one extra frame, so a database round trip per event would buy nothing — and claiming events would stop the read model being rebuilt by replaying the topic, which is the point of ED-44.
- **Alternative:** Deduplicating anyway for symmetry (cost with no benefit); one consumer doing both jobs (couples the map's latency to the event log's writes, and a rebuild would duplicate history).
- **Tradeoff:** The two consumer groups make different correctness claims about the same stream, so the difference has to be documented or the next reader will assume the live feed is exactly-once. It is written on the class and in the phase doc.

## ED-47 Server-sent events, and a client that cannot keep up is dropped (Phase 10)
- **Decision:** `GET /api/tracking/stream` is SSE. Frames are written straight to each emitter; a client whose write fails is removed and expected to reconnect and re-read `/api/tracking/drivers`. A heartbeat goes out every 20 s.
- **Reason:** The traffic is one-way, so WebSockets would add a protocol without removing a problem; SSE is plain HTTP, so proxies and the browser's own reconnection work. Dropping a stalled client bounds server memory by the number of clients instead of by how far behind the slowest one is — and the recovery is cheap, because the full state is one GET away.
- **Alternative:** WebSockets (needed if the dashboard ever sends messages upstream); a bounded per-client queue (postpones the same decision while holding memory, and the client still ends up behind); an unbounded queue (one bad connection can exhaust the heap); polling every few seconds (simplest, and the map lags by the interval).
- **Tradeoff:** A client on a slow link is disconnected rather than served slowly, and no frames are buffered for it, so a dropped client loses every event between the disconnection and its refetch. Fan-out is also per instance: two application instances each serve only the clients connected to them, which only works because every instance consumes the whole topic.

## ED-48 Delay alerts are suppressed by a threshold and by growth, and remembered in a table (Phase 10)
- **Decision:** A delivery is reported late only when the sequencer predicts it missing its window by more than 60 s; an already-reported delay is reported again only once it has grown by 5 minutes. `delivery_alert` holds what has been reported and is deleted when the delivery is no longer late or no longer active.
- **Reason:** The sweep runs every 15 s, so without suppression one late delivery would produce 240 identical alerts an hour and dispatchers would stop reading them. The threshold ignores noise from the routing model's own precision; the growth rule means a re-alert carries new information ("worse than you were told"). The table is what makes "already reported" a fact that survives a restart rather than in-memory state.
- **Alternative:** Publishing an event every sweep and letting consumers deduplicate (pushes the judgement onto every reader, and the stream becomes mostly repetition); a fixed cool-down in time rather than growth (quiet about a delay that doubles); keeping the last alert in memory (lost on restart, wrong with two instances).
- **Tradeoff:** A delay that worsens slowly — four minutes over an hour — is only reported once. The thresholds are settings with defaults chosen by judgement, not by measurement against real delivery data, which the project does not have.

## ED-49 A traffic change triggers the sweep immediately, from a separate component (Phase 10)
- **Decision:** `RoadNetworkProvider.replaceTraffic` publishes a `TrafficChangedEvent`; `TrafficRecalculationTrigger` listens after the commit and sweeps at once. The timed sweep lives in a different component (`DeliveryWatchScheduler`) that can be switched off independently.
- **Reason:** FR-22 is about reacting to a road change, and waiting up to 15 s for the next timer run makes the reaction look like a coincidence. Splitting the two is not cosmetic: the test suite needs the traffic reaction on while running the sweep by hand, and with both in one component switching off the timer would have switched off the feature being tested.
- **Alternative:** Only the timer (simpler, slower to react, and FR-22 untestable without waiting); calling the sweep inline inside `replaceTraffic` (the traffic API's response time would then include every driver's route); a Kafka event consumed by a listener (the network lives in memory in the same instance, so this would be a round trip to learn what the instance just did).
- **Tradeoff:** A burst of traffic updates can trigger overlapping sweeps; nothing debounces them today, and at ~50 ms per driver that is real CPU. The scheduler's pool bounds the damage but does not prevent it — a debounce is the first thing to add if traffic updates ever become frequent.

## ED-50 Simulated data is opt-in and labelled everywhere it travels (Phase 10)
- **Decision:** Both simulators are off unless enabled, log a warning when on, and are reported by `GET /api/simulation/status`. Every position carries `LocationSource.API` or `SIMULATION` through the event payload, the read model and the SSE frame. The traffic simulator writes through the same validated path as the traffic API.
- **Reason:** There are no devices, so exercising live tracking at all means inventing movement — and invented data that is indistinguishable from real data eventually gets reported as real. A field that travels with the value is the only label that survives being passed on; a flag in the configuration does not reach the dashboard.
- **Alternative:** A separate simulation topic or database (duplicates every consumer, and the two paths then differ); recorded fixture traces (more realistic, and nothing to record from); no simulators at all (nothing to test the live path with).
- **Tradeoff:** An extra field on an event payload and on the position model, and the label is only as good as the code that sets it. Nothing in the project uses simulated output as a measurement, which is the rule the label exists to protect.

## ED-51 The scheduler gets a thread pool, sized from a measurement (Phase 10)
- **Decision:** `spring.task.scheduling.pool.size` is 4 by default (`SCHEDULING_THREADS`).
- **Reason:** Spring's scheduler runs one thread by default, shared by the outbox relay, the 15 s tracking sweep, the SSE heartbeat and both simulators. Measuring the live stream found the consequence: shortening the relay interval from 500 ms to 50 ms made the map *worse* (p95 1.9 s → 5.5 s, worst case 29 s) because the faster relay only competed for that one thread more often. With four threads the same configuration gives p50 122 ms / p95 219 ms.
- **Alternative:** Leaving the default and documenting the interaction (a future scheduled job would hit it again); a dedicated executor per job (more precise, more configuration than this project needs); moving the relay out of the scheduler entirely to its own thread (what to do if the relay ever needs to keep up with a high write rate).
- **Tradeoff:** Four threads is headroom for the five jobs that exist, not a tuned number, and it does not make any single job faster — a sweep that takes 2.6 s still takes 2.6 s. Parallel scheduled jobs also means two of them can now touch the same rows at once; each sweep driver is its own transaction, which is what makes that safe.
