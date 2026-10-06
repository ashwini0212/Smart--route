# Phase 4: Domain model and database

## STEP 1: What we built

| Module | Contents |
|---|---|
| `common` | `BaseEntity` (id, timestamps, `@Version`), the `ApiError` format and global exception handler, correlation-id filter, `PageResponse` |
| `warehouse` | Hubs that orders ship from: create, update, activate/deactivate, list |
| `fleet` | Vehicles (type, capacity, status) and drivers (status, current load, last known location) |
| `order` | Delivery orders with a strict status state machine and a full status history |
| `demo` | Deterministic **fictional** seed data: 4 hubs, 120 drivers with vehicles, 600 orders |
| `config` | `Clock` bean (testable time), OpenAPI metadata |
| `db/migration/V1__core_domain.sql` | Flyway migration: 5 tables, CHECK constraints, 5 indexes, 2 sequences |

The API is documented at `/swagger-ui.html` (OpenAPI JSON at `/v3/api-docs`). Endpoints are open in this phase; Phase 5 adds login and role checks to every one of them.

## STEP 2: Architecture

```
            ┌──────────── modular monolith (one Spring Boot app) ────────────┐
 HTTP ───►  │ Controller ─► Service ─► Repository (package-private) ─► JPA  │ ─► PostgreSQL
            │   warehouse        fleet            order          demo       │    (Flyway owns
            │        ▲              ▲  services call each other by id      │     the schema)
            └────────┴──────────────┴───────────────────────────────────────┘
```

Rules, enforced by `ArchitectureTest` (ArchUnit), so they can't silently erode:
1. **Repositories are package-private.** Only a module's own service touches its tables. The `order` module asks `WarehouseService.requireActive(id)`; it never queries the warehouse table.
2. **Controllers never touch repositories.**
3. **The `algorithms` module has no Spring dependency** (already enforced by the Maven build).
4. **Cross-module references are ids, not JPA relationships.** `DeliveryOrder.warehouseId` is a `Long`, not a `@ManyToOne Warehouse`. That avoids lazy-loading surprises and N+1 queries, and keeps each module separable. Inside a module the database still enforces the foreign key.

`ddl-auto: validate`: Hibernate never changes the schema, it only checks that entities match what Flyway created. A mismatch fails at startup, not in production traffic. `open-in-view: false`: no database access during JSON rendering, so lazy-loading bugs show up in tests.

## STEP 3: Data model

```
warehouse 1 ──── * driver ──── 0..1 vehicle
    │
    └──── * delivery_order 1 ──── * order_status_history
```

| Table | Key columns | Notes |
|---|---|---|
| `warehouse` | `code` (unique), lat/lon, `active` | Orders can only be created at an active hub |
| `vehicle` | `plate_number` (unique), `type` BIKE/VAN/TRUCK, capacity kg and m³ | |
| `driver` | `code` `DRV-000001`, `status`, `current_load_*`, `active_delivery_count`, last location | One vehicle per driver (unique FK) |
| `delivery_order` | `code` `ORD-000001`, priority, status, weight/volume, time window | |
| `order_status_history` | from → to, reason, time | Written in the same transaction as every status change |

Every rule that the data itself must obey is a **CHECK constraint**, not only Java validation: coordinates in range, positive weights, known enum values, `window_start < window_end`. Validation in the API gives friendly errors; constraints guarantee that no bug, script or manual SQL can store an impossible row.

### Order state machine

```
CREATED ──► ASSIGNED ──► PICKED_UP ──► IN_TRANSIT ──► DELIVERED
   │           │  │           │             │
   │           │  └─► CREATED │ (unassign)  └──► FAILED
   └──► CANCELLED ◄┘          └──► FAILED
```
`OrderStatus.canTransitionTo` holds the table; `OrderService.transition(id, target, reason)` is the only way to change a status. An illegal move returns `409 INVALID_STATE_TRANSITION`. 15 tests cover every legal and illegal pair.

### Order and driver codes
Readable codes come from PostgreSQL **sequences** (`driver_code_seq`, `order_code_seq`). Two concurrent requests can never get the same number, which `SELECT max(...) + 1` cannot guarantee.

### Concurrency: optimistic locking
Each table has a `version` column (`@Version`). If two dispatchers load the same order and both save, the second update matches zero rows and fails with `409 CONCURRENT_MODIFICATION` instead of silently overwriting the first. `OrderConcurrencyTest` reproduces this with two stale copies. Pessimistic row locks (`SELECT ... FOR UPDATE`) will be used in Phase 7 where the assignment engine must reserve a driver's capacity.

## STEP 4: Contracts

| Method | Path | Result |
|---|---|---|
| GET/POST | `/api/warehouses` | list / create |
| GET/PUT | `/api/warehouses/{id}` | read / update |
| PUT | `/api/warehouses/{id}/active` | activate or deactivate |
| GET/POST | `/api/drivers` | paged list (filter by status) / create |
| GET/PUT | `/api/drivers/{id}` | read / update |
| PUT | `/api/drivers/{id}/status` | change availability (not to/from ON_DELIVERY by hand) |
| GET/POST | `/api/vehicles`, GET `/api/vehicles/{id}`, PUT `/api/vehicles/{id}/status` | vehicles |
| POST | `/api/orders` | create (validated: coordinates, weight, window) |
| GET | `/api/orders?status=&priority=&warehouseId=&createdFrom=&createdTo=&page=&size=` | search, newest first, page size 1–100 |
| GET | `/api/orders/{id}`, `/api/orders/{id}/history` | order, status history |
| POST | `/api/orders/{id}/cancel` | cancel with a reason |

Every error uses one format:
```json
{"timestamp":"2026-10-06T11:10:02.738Z","status":404,"code":"RESOURCE_NOT_FOUND",
 "message":"Order 999999 was not found","path":"/api/orders/999999",
 "traceId":"0a3e6b2c-7252-428f-93af-a926aea04370","fieldErrors":[]}
```
(real response from the seeded app). The `traceId` is the `X-Request-Id` header if the client sent a safe one, otherwise a new UUID; it is also in every log line, so a user's error report can be matched to the server logs. Unexpected exceptions return `INTERNAL_ERROR` with a generic message; the stack trace only goes to the log.

## STEP 5: Seed data
`SPRING_PROFILES_ACTIVE=seed` (default in `docker compose`) fills an **empty** database: 4 hubs, 120 drivers with vehicles, 600 orders. Fixed random seed, so every run produces the same data. All people are **fictional**: generated names, phone numbers in the unused `+91 90000 xxxxx` range, invented street addresses inside a Bengaluru bounding box. No real personal data is used. Seeding 724 rows took ~9 s on this VM through JPA; Phase 13 will switch to batch inserts if that matters.

## STEP 6: Tests and verification

56 app tests run against a **real PostgreSQL 17 in Testcontainers** (not H2, whose SQL dialect, CHECK and partial-index behaviour differ):
- API tests for warehouses (7), fleet (9), orders (8): happy paths, validation errors with field details, 404s, duplicates, illegal transitions.
- State machine (15), vehicle type rules, optimistic locking, seeder determinism and idempotency (3), architecture rules (4), app context and error format (8).

### Index check with EXPLAIN ANALYZE (real runs)
The seeded database has only 600 orders, too few for the planner to prefer an index. To check that the indexes are used at realistic volume, I added 200,000 synthetic order rows (status mix: 60 % delivered, 10 % each cancelled/failed/created/in transit) to the compose PostgreSQL 17 and ran `ANALYZE`. Numbers are single runs on a shared VM, so read them as orders of magnitude.

| Query | Plan with index | Time | Without index | Time |
|---|---|---|---|---|
| Dispatch queue: newest 20 `CREATED` orders | Index Scan Backward on `idx_order_status_created` | 0.15 ms | Parallel Seq Scan + top-N sort | 45 ms |
| Delay check: active orders with `window_end < now()` (19,988 rows) | Index Scan on partial `idx_order_active_window` | 15 ms | Seq Scan | 92 ms |
| History of one order | Index Scan on `idx_order_status_history_order` | 0.12 ms | n/a | |
| Available drivers at a hub (120 drivers) | Seq Scan, as expected for a 120-row table | 0.08 ms | | |

The partial index is **800 kB vs 13 MB** for the full `(status, created_at)` index, because it only contains the 10 % of orders that are still moving. Delivered orders, which are most of the table over time, never enter it.

### Running it yourself
```bash
cp .env.example .env
docker compose up --build
curl 'localhost:8080/api/orders?status=CREATED&size=5'
open http://localhost:8080/swagger-ui.html
```

## STEP 7: Review notes
- The backend Docker image failed in CI after Phase 3 because the reactor listed the new `benchmarks` module that the Dockerfile didn't copy. Fixed: the image builds only `app` and what it needs (`-pl app -am`), so JMH code never ships in it.
- The partial index `idx_driver_available` is not used at 120 rows. That is correct planner behaviour; it pays off at thousands of drivers, which Phase 13 load tests will check rather than assume.

## Interview questions
1. Why reference other modules' entities by id instead of `@ManyToOne`? What do you give up?
2. What does `@Version` do at the SQL level? When would you choose `SELECT ... FOR UPDATE` instead?
3. Why validate both in the API and with database CHECK constraints?
4. Why are order codes generated by a database sequence and not `max(code) + 1`?
5. What is a partial index? Why does `idx_order_active_window` stay small as the table grows?
6. Why does PostgreSQL ignore an index on a 120-row table?
7. Why test against real PostgreSQL in Testcontainers instead of H2?
8. Why `ddl-auto: validate` and `open-in-view: false`?
9. How would you add a NOT NULL column to `delivery_order` with millions of rows without downtime?
10. How does the `traceId` help when a user reports an error?
