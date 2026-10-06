# Phase 7: Driver assignment

## STEP 1: What we built

| Piece | Purpose |
|---|---|
| `assignment.CandidateService` | Ranks drivers for an order: radius pre-filter → hard rules → road ETAs → score → top-k |
| `assignment.AssignmentScorer` | The weighted score **[HEURISTIC]**: ETA, workload, capacity fit |
| `assignment.AssignmentService` | Manual assignment and greedy auto-dispatch, each under row locks |
| `assignment.DriverLocationIndex` | Redis GEO radius search, with a database scan as fallback |
| `assignment.AssignmentConfig` + `/api/admin/assignment-config` | Weights and limits an admin can change without a deploy |
| `assignment.Assignment` + `V4__assignment.sql` | Every decision stored with its score breakdown (audit) |
| `routing.EtaService` | Travel times from many drivers to one pickup with **one** Dijkstra run |
| `order` module | `driverId`/`assignedAt` on orders, assign/unassign, the driver's own delivery endpoints |
| `fleet.DriverService.reserveCapacity` | The locked, re-checked capacity booking |
| `algorithms.selection.TopK` | Best k of n with a bounded heap |

## STEP 2: Architecture

```
POST /api/assignments {orderId, driverId}        GET /api/assignments/candidates?orderId=&k=
        │                                                       │
        ▼                                                       ▼
AssignmentService                                      CandidateService.rank
  rank (outside the transaction)                         pickup = order's warehouse
  │                                                      1. Redis GEO: drivers within R m        O(log N + m)
  ▼ ── transaction ──────────────────────────┐           2. PostgreSQL: their state, hard rules  O(m)
  │  SELECT order  FOR UPDATE                │           3. nearest maxCandidates (straight line)
  │  re-check: still CREATED?                │           4. one Dijkstra on the reversed graph    → ETAs
  │  SELECT driver FOR UPDATE                │           5. score each [HEURISTIC]                O(c)
  │  re-check: on shift, vehicle, capacity,  │           6. top k, bounded heap                   O(c log k)
  │            active count                  │
  │  driver.addLoad, order → ASSIGNED        │
  │  INSERT assignment (score breakdown)     │
  └──────────────────────────────────────────┘
```

Lock order is always **order row, then driver row**. Two assignments can therefore wait for each other but
never deadlock. The ranking runs before the transaction on purpose: a JPA query returns an entity already in
the persistence context as it was first read, even under `FOR UPDATE`, so a transaction that had already read
the driver would check a stale load.

```
Order status change ──► OrderStatusChangedEvent ──► AssignmentLifecycle
  (DELIVERED/FAILED/CANCELLED/unassigned)            releaseCapacity: load -= order, count -= 1
                                                     count 0 → ON_DELIVERY becomes AVAILABLE
Driver → OFFLINE ──► DriverStatusChangedEvent ──►    ASSIGNED orders go back to the queue (reason in history)
                                                     PICKED_UP / IN_TRANSIT stay with the driver + warning log
```

Both listeners are synchronous and run inside the transaction that caused them, so the order and the driver
can never disagree about who carries what. The database enforces the same invariant: a CHECK constraint
rejects any active order without a driver.

## STEP 3: Design decisions explained

### 3.1 Why pessimistic locks here, when Phase 4 used `@Version`
Optimistic locking answers "did anyone else change this row?" after the fact. Assignment needs to *decide*
based on the current load: "does this order still fit?". Between the ranking and the write, another dispatcher
may have taken the last 50 kg. So the driver row is locked, re-read and re-checked inside the transaction.

`AssignmentConcurrencyTest` is the proof, not an assumption: 10 threads assigning 100 kg orders to one 600 kg
van end with exactly 6 assignments, `current_load_kg = 600` and 6 assignment rows; 8 threads assigning the
same order end with exactly 1. I removed the two `@Lock` annotations and re-ran: both tests fail with
`ObjectOptimisticLockingFailureException` instead, so the test really depends on the locks.

### 3.2 The score, and what it is not [HEURISTIC]

```
         w_eta × priorityFactor × (1 − min(eta, cap)/cap)
       + w_workload            × (1 − active/maxActive)
score = ──────────────────────────────────────────────────────  ∈ [0, 1]
       + w_capacity            × capacityFit
                    Σ weights
```
- `priorityFactor`: LOW 0.75, NORMAL 1, HIGH 1.5, URGENT 2. An urgent order cares about speed more than
  about spreading work; the test `urgentOrdersWeighEtaMoreThanWorkload` pins that behaviour down.
- `capacityFit` = max(weight / remaining kg, volume / remaining m³): **best fit**, as in bin packing. A 500 kg
  order prefers a van it nearly fills over an empty truck, keeping trucks free for freight only they can carry.
- Dividing by Σ weights keeps the score in [0, 1] whatever an admin configures, so scores stay comparable
  across configuration changes.

This is a judgement call expressed as arithmetic. It does not optimize any global objective, which is why
every response carries `"algorithm": "weighted score ... [HEURISTIC]"` and the full breakdown per candidate.
Setting `workloadWeight` and `capacityWeight` to 0 reduces it exactly to "nearest driver", which is how the
experiment in STEP 6 gets its baseline.

### 3.3 Hard rules are not part of the score
On shift, an ACTIVE vehicle, a big enough vehicle class, remaining weight and volume, and the active-delivery
limit are **filters**, not terms: no weight can make it acceptable to give a 900 kg pallet to a bike. The
ranking reports how many drivers each rule removed (`excluded: {"VEHICLE_TOO_SMALL": 1, ...}`), so "why is
this order not assigned?" has an answer a dispatcher can act on.

### 3.4 One Dijkstra for all ETAs, on the reversed graph
Travel time **to** the pickup is what matters. Searching the normal graph from the pickup would give times
*from* it, which differ on one-way streets. So each network snapshot carries `graph.reversed()`, and one
search from the pickup on that graph gives every driver's ETA at once, stopping as soon as all candidate
nodes are settled. The auto-dispatcher goes further: all orders of one warehouse share a pickup, so it
computes one full tree per warehouse per run and reuses it.

Costs are exact travel times from the same A*/Dijkstra core as the routing API, so a candidate's `etaSeconds`
equals what `POST /api/routes/fastest` returns for the same two points (asserted in
`etaIsTheFastestRoadTimeFromDriverToWarehouse`). Straight-line distance is used only for the pre-filter.

### 3.5 Auto-dispatch is greedy, and says so
Orders leave a priority queue (priority desc, deadline asc, age asc) and each takes the best driver available
at that moment. This is not an optimal matching: an urgent order can take a driver that a later order needed
more. The alternative, an optimal assignment over the whole batch (Hungarian algorithm, O(n³)), would be
optimal only for one snapshot of driver positions and would be all-or-nothing. Each order is instead assigned
in its own transaction, so one failure costs one order; if the top candidate was taken meanwhile, the next two
are tried before the order is reported as unassigned, with the reason.

### 3.6 Redis GEO is a pre-filter, never the truth
Driver positions are mirrored into a GEO set (`smartroute:drivers:geo`) after each location change commits.
The radius query is the only step that would otherwise scan every driver. Every candidate's state is then
re-read from PostgreSQL, so the index can never cause a wrong assignment, only a missed candidate. With Redis
down, a haversine scan answers the same question and Redis is skipped for 30 s (the same pattern as the route
cache). Measured: identical results, see STEP 6.

### 3.7 Driver endpoints and the token
`GET /api/deliveries/mine` and `PUT /api/deliveries/{orderId}/status` take the driver id from the signed
token's `driverId` claim, never from the request. Updating someone else's order answers **404, not 403**, so
order ids can't be probed. Only PICKED_UP, IN_TRANSIT, DELIVERED and FAILED can be reported this way;
cancelling is a staff action, assigning is not a status report at all.

## STEP 4: Contracts

| Method | Path | Who | Result |
|---|---|---|---|
| GET | `/api/assignments/candidates?orderId=&k=1..20` | staff | ranking with score breakdown and exclusion counts |
| POST | `/api/assignments` | staff | assign `{orderId, driverId, reason?}` → 201 with the stored decision |
| POST | `/api/assignments/auto?limit=1..1000` | staff | greedy run: assigned list, not-assigned list with reasons |
| GET | `/api/assignments?orderId=` | staff, viewer | decisions recorded for an order |
| POST | `/api/orders/{id}/unassign` | staff | ASSIGNED → CREATED, driver released |
| GET | `/api/deliveries/mine` | driver | own active deliveries |
| PUT | `/api/deliveries/{orderId}/status` | driver (own), staff (any) | PICKED_UP / IN_TRANSIT / DELIVERED / FAILED |
| GET | `/api/admin/assignment-config` | staff | current weights and limits |
| PUT | `/api/admin/assignment-config` | ADMIN | change them (next ranking uses them) |

`OrderResponse` gains `driverId` and `assignedAt`; `GET /api/orders` gains a `driverId` filter.

A candidate looks like this (real response field names):
```json
{"rank":1,"driverId":42,"driverCode":"DRV-000042","vehicleType":"VAN","etaSeconds":214.7,
 "straightLineMeters":1180.4,"activeDeliveries":1,"remainingKg":480.00,"remainingM3":3.600,
 "score":0.861,"etaScore":0.881,"workloadScore":0.875,"capacityScore":0.250}
```

Failures use the Phase 4 error format: a rule broken on the locked data is `422 BUSINESS_RULE_VIOLATION`
with the specific reason ("Order does not fit: driver DRV-000042 has 80.00 kg / 0.400 m³ left"), an order
that is no longer waiting is `409 INVALID_STATE_TRANSITION`.

## STEP 5: Tests (265 total, 30 new; `./mvnw verify` green)
- `AssignmentApiTest` (15, PostgreSQL + Redis): ranking order and exclusion counts; a candidate's ETA equals
  the routing API's fastest duration for the same points; workload spreads orders between two drivers at the
  same place; manual assignment reserves capacity, flips the driver to ON_DELIVERY, writes history and the
  audit row; each hard rule rejected with its own message and nothing changed; `maxActiveDeliveries`;
  unassign and cancel release load and return the driver to AVAILABLE; a driver sees and updates only their own
  deliveries (404 for someone else's, 422 for a non-road status, full PICKED_UP → DELIVERED walk); going
  OFFLINE re-queues only orders not yet picked up and coming back on shift with a parcel aboard gives
  ON_DELIVERY; auto-dispatch serves URGENT first and reports why the rest were skipped; the `limit`; the
  search radius; the candidate limit keeps the nearest; only admins change weights; viewers and drivers
  cannot assign.
- `AssignmentConcurrencyTest` (3): the two double-booking races above, plus 4 concurrent auto-dispatch runs
  over 30 orders and 3 vans — exactly 24 assignments (the configured maximum), 24 audit rows, and every
  driver's `active_delivery_count` equal to its real number of assigned orders.
- `AssignmentScorerTest` (6): terms stay in [0, 1], the ETA cap, the normalization (doubling all weights
  changes nothing), priority shifting the ETA/workload balance, ETA-only reducing to nearest-driver,
  capacity fit taking the tighter of weight and volume.
- `TopKTest` (+1 of 6): `k = Integer.MAX_VALUE` (see STEP 7).
- `OrderConcurrencyTest` updated: assigning now needs a driver, because the database says so.

## STEP 6: Measurements
Full output: [docs/benchmarks/phase-7-assignment.txt](../benchmarks/phase-7-assignment.txt). Reproduce with
`scripts/perf/assignment_workload.py`. Seeded data: 120 drivers (105 on shift), 600 waiting orders, synthetic
10k-node city, docker compose on a 4-vCPU VM. Every number below was produced by running it.

### Endpoint latency (one sequential client, 100 requests)

| Request | p50 | p95 |
|---|---|---|
| `GET /api/assignments/candidates?k=5` (105 drivers on shift, 5 km radius) | 17.7 ms | 26.9 ms |
| `GET /api/orders/{id}` (baseline: auth + one query) | 5.4 ms | 6.8 ms |

So the whole ranking — radius search, 50-driver state load, one Dijkstra with traffic, scoring, top-k — costs
about 12 ms on top of request overhead.

### Does the workload term do anything? (configured weights vs ETA only)

**100 orders, fleet not saturated:**

| | configured (0.6 / 0.25 / 0.15) | ETA only (1 / 0 / 0) |
|---|---|---|
| drivers used | 52 of 105 | 30 of 105 |
| deliveries per driver, stdev | 1.13 | 1.95 |
| busiest driver | 4 | 8 |
| mean pickup ETA | 2.7 min | 2.1 min |

**400 orders, fleet saturated:**

| | configured | ETA only |
|---|---|---|
| assigned | 375 | 382 |
| deliveries per driver, stdev | 2.69 | 2.79 |
| busiest driver | 8 | 8 |
| mean pickup ETA | 4.0 min | 3.7 min |

**Reading these honestly:** the workload term does what it is for, but only while there is slack. With 100
orders it spreads the work over 52 drivers instead of 30 and halves the busiest driver's load, and that costs
36 seconds of mean pickup ETA. With 400 orders almost every usable driver hits the limit either way, so the
weights barely matter — the fleet, not the scoring, is the constraint. Neither run is evidence that these
particular weights are *right*; they show what moving them does on this data.

The run itself took 1.5–9 s for 100–400 orders (one ETA tree per warehouse plus one transaction per order).
That is a dispatcher-facing button, not a hot path, and Phase 13 will measure it under concurrent load.

### Redis outage
With `docker compose stop redis`, the candidate endpoint still found the same 56 drivers in radius via the
database scan: the first request paid the 250 ms timeout (278 ms), the next ones took p50 20 ms.
`/actuator/health/readiness` stayed **UP** (it only checks the database) while `/actuator/health` honestly
reported 503. After Redis came back the index was rebuilt automatically once the 30 s back-off expired
(120 members) and returned the same 56 drivers as the scan had.

## STEP 7: Review notes (bugs found by running it)
- **`OutOfMemoryError` on every manual assignment.** Manual assignment ranks all drivers to record the chosen
  one's score, and passed `k = Integer.MAX_VALUE`; `TopK` sized its heap to `k` up front. Every POST
  `/api/assignments` returned 500. Fixed by capping the initial capacity (`Math.min(k, 64)`) and letting the
  heap grow; a regression test in `TopKTest` covers it. Worth noting: this only showed up because the API
  tests exercise the real endpoint, not the service.
- **A stale driver inside the assigning transaction.** The first version ranked candidates inside the
  `@Transactional` method, so the persistence context already held the driver; the later `FOR UPDATE` read
  returned that cached copy with its old load, and the capacity check passed on stale data. The double-booking
  test failed with `ObjectOptimisticLockingFailureException` (the `@Version` net caught it) instead of a clean
  422. Ranking now happens before the transaction opens.
- **The new CHECK constraint broke an old test.** `chk_order_driver_when_assigned` requires a driver on any
  active order, so `OrderConcurrencyTest`, which moved an order to ASSIGNED with no driver, started failing on
  the database. The constraint is right and the test was describing an impossible state; it now assigns a real
  driver. A reminder that a migration can invalidate tests written before it.
- **`assignment_config` is created by a migration, not by tests.** The test database cleaner truncated it,
  so every test after the first ran without weights. It now resets the row to the migration's values instead
  of deleting it.

## Interview questions
1. Why does assignment need `SELECT ... FOR UPDATE` when Phase 4 settled for `@Version`? What exactly can
   happen with only optimistic locking?
2. Why is the lock order (order row, then driver row) part of the design rather than an implementation detail?
3. Why is the candidate ranking computed *outside* the assigning transaction? What went wrong when it wasn't?
4. Why is "the driver's vehicle is too small" a filter and not a term with a large negative weight?
5. Walk through the ETA computation. Why the reversed graph, and why one search instead of one per driver?
6. The auto-dispatcher is greedy. Give a concrete input where it assigns worse than an optimal matching, and
   say why greedy was still chosen.
7. What does `[HEURISTIC]` promise and not promise in this API?
8. Redis holding driver positions goes down. What still works, what gets slower, and what could go wrong?
9. The workload weight changed the standard deviation from 1.95 to 1.13 with 100 orders but not with 400.
   Why? What would you measure next?
10. Why does updating another driver's delivery return 404 instead of 403?
11. A driver goes offline carrying three parcels, one picked up. What happens to each order, and why the
    difference?
12. How would you extend this to assign a *batch* of orders to one driver as a single route (Phase 8)?
