# SmartRoute

A logistics platform that assigns delivery orders to drivers, computes shortest and fastest routes on a road graph, sequences multi-stop deliveries, and streams driver movement to a dispatcher dashboard.

> **Project status: Phase 10 of 15.** Foundation, the graph algorithm core, road-data import, snapping, benchmarks, the order/fleet/warehouse domain on PostgreSQL, login with role-based access, the routing API with a Redis cache, driver assignment, multi-stop optimization, domain events on Kafka and live tracking (positions, a server-sent event stream, delay alerts, recalculation on traffic changes) exist. The dispatcher dashboard, observability and the optional assistant are built in later phases; see [the plan](docs/phase-0-plan.md). This README only describes what exists today.

![Phase 1 frontend shell](docs/images/phase-1-shell.png)

## Problem

Delivery companies continuously decide which driver takes an order, which route to drive, and in what order to visit stops, while traffic and driver availability change. SmartRoute solves these with graph algorithms (Dijkstra, A*), heaps (Top-K driver candidates), greedy assignment and dynamic programming (exact stop ordering for small inputs), on an event-driven Spring Boot backend.

## What works today

| Area | State |
|---|---|
| Backend | Spring Boot 4 modular monolith: warehouses, drivers, vehicles, orders with a status state machine and history; Flyway schema on PostgreSQL; one JSON error format with trace ids; OpenAPI docs; fictional seed data (120 drivers, 600 orders); 255 tests on real PostgreSQL, Redis and Kafka (Testcontainers). See [Phase 4](docs/phases/phase-4-domain-database.md) |
| Routing API | Shortest/fastest routes (A*, optimal) and up to 3 alternatives (heuristic) between any two points, snapped to the road network; traffic multipliers with versioned network snapshots; Redis cache-aside that keeps working when Redis is down; route history. Runs on the **synthetic** city unless a real dataset is configured. See [Phase 6](docs/phases/phase-6-routing-api.md) |
| Assignment | Driver candidates ranked by a weighted score (ETA from the road graph, workload, capacity fit, **heuristic**), manual assignment and greedy auto-dispatch, both safe against double-booking under row locks; driver delivery endpoints; weights editable by an admin. See [Phase 7](docs/phases/phase-7-assignment.md) |
| Multi-stop routes | Visiting order for up to 20 stops: exact (Held-Karp) up to 12 stops, nearest neighbour + 2-opt above, always stating which ran; arrival times with service time, stops that miss their window flagged, capacity enforced; the same for a driver's own deliveries. See [Phase 8](docs/phases/phase-8-multi-stop-optimization.md) |
| Events | Every order status change and driver position goes to Kafka through a transactional outbox: versioned envelope, per-order ordering, consumers that handle a repeated delivery once, blocking retries and a dead-letter topic per event topic; the recorded stream is readable at `/api/events`. See [Phase 9](docs/phases/phase-9-kafka-events.md) |
| Live tracking | Driver positions kept in a Redis read model fed by the Kafka stream (rebuildable, falls back to the database); a server-sent event stream at `/api/tracking/stream` that drops clients which cannot keep up; deliveries predicted to miss their window raised as alerts (threshold and growth suppression, **heuristic** — it is the sequencer's prediction, not a forecast); routes reported as recalculated when the order or duration changes, immediately after a traffic change. See [Phase 10](docs/phases/phase-10-live-tracking.md) |
| Simulators | **[SIMULATION]** Optional, off by default: drivers moved along real roads towards their drop, and random traffic jams. Every simulated position is labelled `SIMULATION` through the event payload, the read model and the stream; `/api/simulation/status` says what is running. Nothing simulated is used as a measurement. See [Phase 10](docs/phases/phase-10-live-tracking.md) |
| Security | JWT login with rotating refresh tokens (HttpOnly cookie, reuse detection), BCrypt, four roles enforced on every endpoint, login rate limiting, CORS allow-list, security headers. See [Phase 5](docs/phases/phase-5-security.md) |
| Frontend | React + TypeScript + Vite + Tailwind shell that shows live backend health, 5 tests (the dispatcher dashboard is Phase 11) |
| Infrastructure | `docker compose up` starts PostgreSQL, Redis, Kafka (KRaft), backend and frontend with health-checked startup order |
| CI | GitHub Actions: backend tests, frontend lint/test/build, Docker image build |
| Road data | OSM import script (tested; real extract not yet committed, see [data/road-network](data/road-network/README.md)), CSV loader, largest-SCC cleanup, k-d tree nearest-node snapping. See [Phase 3](docs/phases/phase-3-road-network-benchmarks.md) |
| Benchmarks | JMH suite with [real results](docs/phases/phase-3-road-network-benchmarks.md#step-6-benchmarks-real-jmh-results) |
| Algorithms | Adjacency-list graph, BFS, iterative DFS, Kosaraju SCC, Dijkstra (point-to-point, one-to-many), A* with haversine heuristics, alternative routes (penalty method), bounded-heap Top-K, exact and heuristic stop sequencing, deterministic synthetic city generator; 116 unit tests. See [Phase 2](docs/phases/phase-2-graph-core.md) |

## Tech stack

Java 21, Spring Boot 4, Maven · PostgreSQL 17 · Redis 8 · Apache Kafka 4 (KRaft) · React 19, TypeScript, Vite, Tailwind CSS 4, Vitest · Docker Compose · GitHub Actions

## Repository layout

```
backend/
  algorithms/   pure Java algorithms module (no Spring dependency, enforced by the build)
  app/          Spring Boot application
  benchmarks/   JMH benchmarks for the algorithms
data/           road-network datasets
frontend/       React dashboard
docs/           plan, engineering decisions, per-phase notes
scripts/        data preparation scripts (later phases)
.github/        CI pipeline
```

## Local setup

Requirements: Docker with Compose v2. For running outside Docker: JDK 21 and Node 22.

```bash
cp .env.example .env          # set your own passwords and JWT_SECRET; .env is git-ignored
docker compose up --build     # first build downloads dependencies
```

| URL | What |
|---|---|
| http://localhost:3000 | Frontend |
| http://localhost:8080/actuator/health | Backend health |
| http://localhost:8080/swagger-ui.html | API documentation (OpenAPI); use "Authorize" with a token from `/api/auth/login` |

With the default `seed` profile, demo logins exist for every role: `admin@smartroute.local`, `dispatcher@smartroute.local`, `viewer@smartroute.local`, `driver1@smartroute.local`, `driver2@smartroute.local`. Their password is whatever you set as `DEMO_USER_PASSWORD` in `.env`. All demo people are fictional.

```bash
curl -s localhost:3000/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"dispatcher@smartroute.local","password":"<DEMO_USER_PASSWORD>"}'
# then: curl -H "Authorization: Bearer <accessToken>" localhost:3000/api/orders
```

Startup order is enforced by health checks: `postgres`, `redis`, `kafka` → `backend` → `frontend`.

### Running without Docker

```bash
docker compose up -d postgres                    # the app needs PostgreSQL
set -a && . ./.env && set +a                     # DB, JWT and demo passwords from .env
cd backend && DB_PASSWORD=$POSTGRES_PASSWORD SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run -pl app -am   # API on :8080
cd frontend && npm install && npm run dev         # UI on :5173, proxies /api and /actuator to :8080
```

## Testing

```bash
cd backend && ./mvnw verify      # all backend tests (needs Docker for Testcontainers)
cd frontend && npm test          # frontend tests
python3 -m unittest discover -s scripts/osm   # data script tests
```

## Algorithms

| Algorithm | Used for | Time | Space |
|---|---|---|---|
| BFS | Fewest-hop path, k-hop neighbourhood | O(V + E) | O(V) |
| Iterative DFS + Kosaraju SCC | Find nodes cut off by one-way streets | O(V + E) | O(V + E) |
| Dijkstra (binary heap, lazy deletion) | Shortest / fastest route, one-to-many ETAs | O((V + E) log V) | O(V + E) |
| Held-Karp (bitmask DP) | Exact visiting order for up to 16 stops | O(n²·2ⁿ) | O(n·2ⁿ) |
| Nearest neighbour + 2-opt | Visiting order above that size **[HEURISTIC]** | O(n²) per pass | O(n) |
| A* (haversine heuristic) | Faster point-to-point route, same optimal cost | O((V + E) log V) worst case | O(V + E) |
| 2-d tree | Snap a GPS point to the nearest road node | O(log V) average query | O(V) |
| Dijkstra on the reversed graph | ETA of every nearby driver to one pickup, in one search | O((V + E) log V) | O(V + E) |
| Bounded heap (Top-K) | Best k driver candidates of n | O(n log k) | O(k) |
| Greedy + priority queue | Auto-dispatch of waiting orders **[HEURISTIC]** | O(n log n + n·ranking) | O(n) |

## Performance

Measured with JMH on a 4-vCPU cloud VM (synthetic 10,000-node city): a local route takes ~0.06 ms, a cross-city route ~1–2 ms; A* is up to 1.8× faster than Dijkstra on a 40k-node city; nearest-node lookup takes ~0.7 µs vs ~6 ms for a linear scan. Full tables, method and caveats: [Phase 3 benchmarks](docs/phases/phase-3-road-network-benchmarks.md#step-6-benchmarks-real-jmh-results).

Multi-stop optimization: ordering 12 stops takes p50 93 ms end to end, of which 2.5 ms is the exact algorithm — the rest is the 2(n+1) graph searches behind it. Against the exact optimum, nearest neighbour + 2-opt averages 2 % longer (worst case 19 %) and is 700× faster at 12 stops. Tables: [Phase 8](docs/phases/phase-8-multi-stop-optimization.md#step-6-measurements).

Assignment (same VM, 120 seeded drivers): ranking the top 5 of 105 drivers on shift takes p50 18 ms / p95 27 ms, about 12 ms above a plain authenticated GET. Switching from the configured weights to ETA only ("nearest driver") on 100 orders concentrated the work on 30 drivers instead of 52 and doubled the busiest driver's load, while cutting mean pickup ETA from 2.7 to 2.1 min; with 400 orders the fleet saturates and the difference nearly vanishes. Method and full output: [Phase 7](docs/phases/phase-7-assignment.md#step-6-measurements).

Domain events are not instant and are not claimed to be: from the commit that changed an order to the event being readable takes p50 474 ms with the default 500 ms relay interval, and p50 59 ms when the relay polls every 50 ms — almost all of it is the wait for the next relay run, not Kafka. Both runs: [Phase 9](docs/phases/phase-9-kafka-events.md#step-6-measurements).

Live tracking, measured end to end (position recorded → frame read by a browser client): p50 122 ms / p95 219 ms with the relay polling every 50 ms, p50 530 ms / p95 878 ms at the default 500 ms. The delay is the outbox relay's interval, not Kafka, Redis or SSE — half a second behind by choice, which is why nothing here is called "real-time". The same measurement found a bug: with Spring's default single scheduler thread, a *faster* relay made the map worse (p95 5.5 s, worst case 29 s) because the relay, the tracking sweep, the heartbeat and the simulators shared one thread; `SCHEDULING_THREADS` now defaults to 4. The delay/recalculation sweep costs ~35–55 ms per driver (1.6–2.6 s for 48 drivers) and will not scale to hundreds of active drivers on one instance. Runs and the full reading: [Phase 10](docs/phases/phase-10-live-tracking.md#step-6-measurements).

Through the HTTP API (one sequential client, docker compose on the same VM, 300 random trips): a computed fastest route takes p50 12 ms / p95 19 ms including the history write; a cached one p50 9 ms / p95 15 ms. Most of that is request overhead, not routing (a trivial GET is p50 4 ms here). Details and the honest reading of these numbers: [Phase 6](docs/phases/phase-6-routing-api.md#step-6-measurements).

## Engineering decisions

Every major decision has a reason, an alternative and a tradeoff in [docs/engineering-decisions.md](docs/engineering-decisions.md).

## Limitations

Everything listed under "What works today" is all that works. No feature from later phases should be assumed.

## License

MIT
