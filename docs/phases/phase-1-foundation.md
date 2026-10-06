# Phase 1: Foundation

## STEP 1: What we built

The skeleton every later phase plugs into:

1. **Backend**: a Maven multi-module build with two modules: `algorithms` (plain Java) and `app` (Spring Boot 4). The app exposes health, liveness and readiness endpoints.
2. **Frontend**: a React + TypeScript + Vite + Tailwind shell. It calls the real backend health endpoint and shows Checking / Operational / Degraded / Unreachable.
3. **Infrastructure**: `docker compose up` starts PostgreSQL, Redis, Kafka, backend and frontend in a health-checked order.
4. **CI**: a GitHub Actions pipeline that fails if any test fails.
5. **Docs**: README (only describes what exists), `engineering-decisions.md`, `.env.example`.

No business features yet. That is deliberate: this phase removes all setup risk so Phase 2 can focus purely on algorithms.

## STEP 2: Architecture of this phase

```
Browser ──► frontend (nginx :80, host :3000)
              │  /actuator/health, /api/*   (same-origin proxy)
              ▼
           backend (Spring Boot :8080)
              │  depends_on: healthy
              ├── postgres :5432   (used from Phase 4)
              ├── redis    :6379   (used from Phase 6)
              └── kafka    :9092   (used from Phase 9)
```

### Why the browser never calls the backend directly
In development, Vite's dev server proxies `/api` and `/actuator` to `localhost:8080`; in Docker, nginx does the same to `backend:8080`. The browser only sees one origin, so:
- no CORS configuration is needed for our own UI,
- development and Docker behave identically,
- the backend port doesn't have to be reachable from the browser in production.

### Why startup order uses health checks
`depends_on` without a condition only waits until the dependency's container *process starts*. PostgreSQL takes a few seconds before accepting connections, and Kafka 15–30 s. `condition: service_healthy` waits until each service's own health check passes:

| Service | Health check |
|---|---|
| postgres | `pg_isready` |
| redis | `redis-cli ping` |
| kafka | `kafka-broker-api-versions.sh` against the broker |
| backend | `GET /actuator/health/readiness` returns UP |
| frontend | nginx serves `/` |

### Liveness vs readiness (common interview topic)
- **Liveness**: "is the process stuck?" If it fails, the orchestrator *restarts* the container.
- **Readiness**: "can it serve traffic right now?" If it fails, the orchestrator *stops sending traffic* but doesn't restart. For example, during startup or while a dependency is down.

Restarting an app because its database is down would make things worse, which is why the two are separate.

### Backend Docker image
Multi-stage: a JDK stage builds the jar, a JRE-only stage runs it as a non-root user. The jar is extracted into Spring Boot *layers* (dependencies, loader, snapshot dependencies, application). Docker caches each layer, so changing one Java class only rebuilds the small top layer, not 30+ MB of dependencies.

## STEP 3: DSA in this phase

None, honestly. Phase 1 has no algorithms. The `algorithms` module exists empty so that Phase 2 starts with the boundary already enforced: `algorithms/pom.xml` has no Spring dependency, so any Spring import there fails compilation.

## STEP 4: Contracts

| Endpoint | Public | Response |
|---|---|---|
| `GET /actuator/health` | yes | `{"status":"UP","groups":["liveness","readiness"]}`; no component details |
| `GET /actuator/health/liveness` | yes | `{"status":"UP"}` |
| `GET /actuator/health/readiness` | yes | `{"status":"UP"}` |
| `GET /actuator/info` | yes | app name, description, version |
| `GET /actuator/env`, `/beans`, ... | **no** (404) | not exposed, since they leak configuration |

Frontend contract: `fetchHealth()` in `src/services/healthApi.ts` returns `{ status }` or throws when the response isn't JSON (for example an nginx 502 HTML page).

## STEP 5: Implementation map

| File | Purpose |
|---|---|
| `backend/pom.xml` | Parent POM: Spring Boot BOM, Java 21, module list |
| `backend/algorithms/pom.xml` | Pure-Java module (JUnit + AssertJ only) |
| `backend/app/pom.xml` | `spring-boot-starter-webmvc`, `actuator` |
| `backend/app/src/main/resources/application.yml` | Port from env, no stack traces in errors, minimal actuator exposure, probes |
| `backend/Dockerfile` | Multi-stage, layered, non-root, health check |
| `frontend/vite.config.ts` | Tailwind plugin, dev proxy, Vitest config |
| `frontend/src/services/healthApi.ts` | HTTP call (services layer) |
| `frontend/src/hooks/useBackendHealth.ts` | Loading / loaded / unreachable state, aborts on unmount |
| `frontend/src/components/SystemStatusCard.tsx` | Rendering only |
| `frontend/nginx.conf` | Static files, API proxy, security headers, SPA fallback |
| `docker-compose.yml` | Five services, health checks, localhost-only ports |
| `.github/workflows/ci.yml` | backend, frontend jobs, then docker job (`needs`) |

Frontend layering (service → hook → component) is the pattern every later page follows: components never call `fetch` directly.

## STEP 6: Test results (actual runs, 2026-10-06)

| Check | Result |
|---|---|
| `./mvnw verify` (backend) | 5 tests, 0 failures. Health UP, liveness/readiness exposed, no component details, info returns app name, `/actuator/env` and `/beans` return 404 |
| `npm run lint` | 0 warnings |
| `npm test` (Vitest) | 5 tests, 0 failures. Checking, Operational, Degraded (503 DOWN), Unreachable (network error), Unreachable (HTML 502) |
| `npm run build` | OK, 222 kB JS (69.6 kB gzip) |
| `docker compose up` | All 5 services healthy |
| Manual smoke | Health via backend and via nginx = UP; nginx security headers present; `/actuator/env` = 404; created and deleted a 3-partition Kafka topic; `psql` query OK (PostgreSQL 17.11); Redis `PONG`; backend runs as user `smartroute` |
| GitHub Actions | **Not run yet.** No GitHub repository exists. It runs on the first push. |

## STEP 7–8: Review notes and fixes made

- nginx gotcha found and fixed: an `add_header` inside a `location` block silently drops all `add_header` lines inherited from the `server` block. The `/assets/` block used `add_header Cache-Control`, which would have removed the security headers for assets; switched to `expires`.
- The `.env` file is git-ignored; only `.env.example` is committed. Compose binds every port to `127.0.0.1`, so the database isn't exposed to your Wi-Fi network.

## Interview questions (answer these; I'll evaluate before Phase 2)

1. Why is `algorithms` a separate Maven module instead of just a package inside the Spring Boot app? What does the compiler now guarantee?
2. What is the difference between a liveness probe and a readiness probe? What would go wrong if the database being down made liveness fail?
3. `depends_on: [postgres]` is already in many tutorials. Why is it not enough, and what does `condition: service_healthy` add?
4. Why does the Dockerfile copy the `pom.xml` files and download dependencies *before* copying the source code?
5. Why do we not expose `/actuator/env` publicly? Name one thing an attacker could learn from it.
6. In the frontend, why does `useBackendHealth` use an `AbortController`? What bug happens without it?
7. Why does the CI `docker` job have `needs: [backend, frontend]`?
