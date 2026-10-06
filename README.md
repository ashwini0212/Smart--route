# SmartRoute

A logistics platform that assigns delivery orders to drivers, computes shortest and fastest routes on a road graph, sequences multi-stop deliveries, and streams driver movement to a dispatcher dashboard.

> **Project status: Phase 7 of 15.** Foundation, the graph algorithm core, road-data import, snapping, benchmarks, the order/fleet/warehouse domain on PostgreSQL, login with role-based access, the routing API with a Redis cache, and driver assignment exist. Multi-stop optimization, Kafka events and the dashboard are built in later phases; see [the plan](docs/phase-0-plan.md). This README only describes what exists today.

![Phase 1 frontend shell](docs/images/phase-1-shell.png)

## Problem

Delivery companies continuously decide which driver takes an order, which route to drive, and in what order to visit stops, while traffic and driver availability change. SmartRoute solves these with graph algorithms (Dijkstra, A*), heaps (Top-K driver candidates), greedy assignment and dynamic programming (exact stop ordering for small inputs), on an event-driven Spring Boot backend.

## What works today

| Area | State |
|---|---|
| Backend | Spring Boot 4 modular monolith: warehouses, drivers, vehicles, orders with a status state machine and history; Flyway schema on PostgreSQL; one JSON error format with trace ids; OpenAPI docs; fictional seed data (120 drivers, 600 orders); 178 tests on real PostgreSQL and Redis (Testcontainers). See [Phase 4](docs/phases/phase-4-domain-database.md) |
| Routing API | Shortest/fastest routes (A*, optimal) and up to 3 alternatives (heuristic) between any two points, snapped to the road network; traffic multipliers with versioned network snapshots; Redis cache-aside that keeps working when Redis is down; route history. Runs on the **synthetic** city unless a real dataset is configured. See [Phase 6](docs/phases/phase-6-routing-api.md) |
| Assignment | Driver candidates ranked by a weighted score (ETA from the road graph, workload, capacity fit, **heuristic**), manual assignment and greedy auto-dispatch, both safe against double-booking under row locks; driver delivery endpoints; weights editable by an admin. See [Phase 7](docs/phases/phase-7-assignment.md) |
| Security | JWT login with rotating refresh tokens (HttpOnly cookie, reuse detection), BCrypt, four roles enforced on every endpoint, login rate limiting, CORS allow-list, security headers. See [Phase 5](docs/phases/phase-5-security.md) |
| Frontend | React + TypeScript + Vite + Tailwind shell that shows live backend health, 5 tests |
| Infrastructure | `docker compose up` starts PostgreSQL, Redis, Kafka (KRaft), backend and frontend with health-checked startup order |
| CI | GitHub Actions: backend tests, frontend lint/test/build, Docker image build |
| Road data | OSM import script (tested; real extract not yet committed, see [data/road-network](data/road-network/README.md)), CSV loader, largest-SCC cleanup, k-d tree nearest-node snapping. See [Phase 3](docs/phases/phase-3-road-network-benchmarks.md) |
| Benchmarks | JMH suite with [real results](docs/phases/phase-3-road-network-benchmarks.md#step-6-benchmarks-real-jmh-results) |
| Algorithms | Adjacency-list graph, BFS, iterative DFS, Kosaraju SCC, Dijkstra (point-to-point, one-to-many), A* with haversine heuristics, alternative routes (penalty method), bounded-heap Top-K, deterministic synthetic city generator; 87 unit tests. See [Phase 2](docs/phases/phase-2-graph-core.md) |

Kafka is running but not yet used; it is connected in Phase 9.

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
| A* (haversine heuristic) | Faster point-to-point route, same optimal cost | O((V + E) log V) worst case | O(V + E) |
| 2-d tree | Snap a GPS point to the nearest road node | O(log V) average query | O(V) |
| Dijkstra on the reversed graph | ETA of every nearby driver to one pickup, in one search | O((V + E) log V) | O(V + E) |
| Bounded heap (Top-K) | Best k driver candidates of n | O(n log k) | O(k) |
| Greedy + priority queue | Auto-dispatch of waiting orders **[HEURISTIC]** | O(n log n + n·ranking) | O(n) |

## Performance

Measured with JMH on a 4-vCPU cloud VM (synthetic 10,000-node city): a local route takes ~0.06 ms, a cross-city route ~1–2 ms; A* is up to 1.8× faster than Dijkstra on a 40k-node city; nearest-node lookup takes ~0.7 µs vs ~6 ms for a linear scan. Full tables, method and caveats: [Phase 3 benchmarks](docs/phases/phase-3-road-network-benchmarks.md#step-6-benchmarks-real-jmh-results).

Assignment (same VM, 120 seeded drivers): ranking the top 5 of 105 drivers on shift takes p50 18 ms / p95 27 ms, about 12 ms above a plain authenticated GET. Switching from the configured weights to ETA only ("nearest driver") on 100 orders concentrated the work on 30 drivers instead of 52 and doubled the busiest driver's load, while cutting mean pickup ETA from 2.7 to 2.1 min; with 400 orders the fleet saturates and the difference nearly vanishes. Method and full output: [Phase 7](docs/phases/phase-7-assignment.md#step-6-measurements).

Through the HTTP API (one sequential client, docker compose on the same VM, 300 random trips): a computed fastest route takes p50 12 ms / p95 19 ms including the history write; a cached one p50 9 ms / p95 15 ms. Most of that is request overhead, not routing (a trivial GET is p50 4 ms here). Details and the honest reading of these numbers: [Phase 6](docs/phases/phase-6-routing-api.md#step-6-measurements).

## Engineering decisions

Every major decision has a reason, an alternative and a tradeoff in [docs/engineering-decisions.md](docs/engineering-decisions.md).

## Limitations

Everything listed under "What works today" is all that works. No feature from later phases should be assumed.

## License

MIT
