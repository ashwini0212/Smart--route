# Phase 10: Live tracking — positions, a live stream, delay alerts and recalculation

## STEP 1: What we built

| Piece | Purpose |
|---|---|
| `tracking.LivePositions` | Each driver's latest position in Redis, fed by the Kafka location events; falls back to the database |
| `tracking.LiveFeedListener` | Second consumer group: updates the read model and pushes a frame to every dashboard |
| `tracking.LiveStream` + `TrackingController` | Server-sent events at `GET /api/tracking/stream`, with a heartbeat and a rule for clients that fall behind |
| `tracking.DeliveryWatch` | The sweep: who is predicted to miss their window, whose route has changed (FR-21, FR-22) |
| `tracking.DeliveryWatchScheduler` / `TrafficRecalculationTrigger` | When the sweep runs: on a timer, and immediately after traffic changes |
| `tracking.TrackingLifecycle` | Clears a delay alert when the delivery is no longer active |
| `V6__tracking.sql` | `delivery_alert` (what has been reported), `driver_route_snapshot` (the last route per driver) |
| `routing.TrafficChangedEvent` | Published when the network's traffic is replaced, so listeners can react |
| `fleet.LocationSource` | `API` or `SIMULATION`, carried with every position into the event payload and the stream |
| `simulation.DriverMovementSimulator` | **[SIMULATION]** Moves drivers along real roads towards their drop; off by default |
| `simulation.TrafficSimulator` | **[SIMULATION]** Slows random segments down and clears them; off by default |
| `simulation.SimulationController` | `GET /api/simulation/status`: is anything being simulated right now? |
| `scripts/perf/live_latency.py` | The stream measurement below, reproducible |

## STEP 2: Architecture

```
driver position (API, or the simulator)
   │  DriverService.updateLocation(..., source)        one transaction
   ├─ UPDATE driver SET last_latitude …
   ├─ DriverLocationChangedEvent → outbox row (Phase 9)
   └─ COMMIT
           │  outbox relay (every 500 ms)
           ▼
   Kafka smartroute.driver-location-updated   key = driverId → one partition per driver, in order
           │
     ┌─────┴───────────────────────────┐
     ▼                                 ▼
  event-log group                  live-feed group
  INSERT system_event              LivePositions.record()  →  Redis, newer timestamp wins
  (exactly once, Phase 9)          LiveStream.publish("driver-moved")
                                            │
                                            ▼  SSE, one frame per connected dashboard
                                   GET /api/tracking/stream
```

```
every 15 s, and immediately after traffic changes
   │
   ▼
DeliveryWatch.sweep(reason)
   for each driver with active deliveries        one transaction per driver
       route = DeliveryRouteService.forDriver(…)     Phase 8 sequencer, from the driver's position
       │
       ├─ route.lateStops()  → more than 60 s late and not already reported
       │        → DELIVERY_DELAYED event + delivery_alert row
       │
       └─ visiting order changed, or duration moved > 10 %
                → ROUTE_RECALCULATED event + driver_route_snapshot row
```

## STEP 3: Design decisions explained

### 3.1 The live map reads a Redis read model, not the driver table
Positions reach Redis by consuming the Kafka topic, not by being written there on the API path. That is what makes it a read model rather than a second source of truth: it can be rebuilt by replaying the topic (or from the database, `POST /api/tracking/positions/rebuild`), losing Redis loses nothing permanent, and the write path does not grow a second place to fail. Reads fall back to the database, so with Redis down the map shows the last committed positions instead of an error.

### 3.2 Out-of-order positions
Kafka keeps one driver's events in order on one partition, which is why the key is the driver id — but a retry, a replay, or a second producer can still deliver an older position after a newer one, and a map that jumps backwards is worse than one that lags. A stored position is only replaced by one with a later timestamp (`LivePositionsTest.anOlderPositionDoesNotReplaceANewerOne`), and the listener does not push a frame it has just discarded.

### 3.3 Why the live-feed consumer does not deduplicate
The event log claims every event in `processed_event` so a duplicate cannot change stored history (Phase 9, ED-41). The live feed deliberately does not: its side effects are a last-write-wins position and a frame in a browser, so handling an event twice writes the same position and sends one extra frame. Paying for a database claim per event would buy nothing and would stop the read model being rebuilt by replaying the topic.

### 3.4 SSE, and what happens to a slow client
Server-sent events rather than WebSockets: the traffic is one-way, it is plain HTTP (so proxies and the browser's own reconnection handle it), and there is no second protocol to operate. The hard part of any live feed is the client that cannot keep up. Frames are written directly to each emitter, and a client whose write fails is dropped and expected to reconnect and re-read `GET /api/tracking/drivers`. A per-client queue would only postpone that decision while holding server memory, so the decision is made where it can be seen. A heartbeat every 20 s keeps an idle stream from being closed by a proxy.

### 3.5 "Predicted to be late" means something specific
The sweep asks the Phase 8 sequencer for the route from where the driver is now, which already returns the arrival time per stop and the stops that miss their window. So a delay alert means: *on the current road network, from the driver's current position, visiting their remaining drops in the computed order, this stop arrives after its deadline*. It is not a statistical forecast, and it inherits the sequencer's caveats — above 12 stops the visiting order is a heuristic, and the only model of time at a stop is a flat 4 minutes.

### 3.6 Alerts are suppressed on purpose
A dispatcher who gets an alert per sweep stops reading alerts. So a delivery must be predicted to arrive more than 60 s past its window before it is reported, and an already-reported delay is only reported again once it has grown by 5 minutes. The `delivery_alert` row is what remembers; it is deleted when the delivery is no longer predicted to be late, so a delivery that catches up and slips again is reported again. `TrackingLifecycle` removes it when the delivery leaves the active statuses, because the sweep only looks at orders a driver still holds and nothing else would ever clean it up.

### 3.7 Recalculation is reported when it changes something
`ROUTE_RECALCULATED` is published when the visiting order changes or the duration moves by more than 10 % — not every sweep, and not on the first one (there is nothing to compare with). The event carries the previous and new duration and the reason ("traffic changed (network version 7)", "scheduled check"), so a reader knows why. The comparison lives in `driver_route_snapshot`, one row per driver.

### 3.8 Simulated data is labelled everywhere it goes
Phase 10 has no devices, so the only way to exercise any of this is to invent movement. Both simulators are off unless asked for, log a warning at startup when on, and `GET /api/simulation/status` says what is running. Every position they produce carries `source: SIMULATION` through the event payload, the read model and the SSE frame, so nothing downstream can mistake it for a driver with a phone. The traffic simulator writes through the same validated path as an administrator's `PUT /api/routing/traffic`, so it cannot put the network in a state the API could not.

What the simulators do **not** model: acceleration, traffic lights, parking, a driver taking a different turn, GPS error, and — for traffic — the fact that real congestion is correlated in space and time. Nothing they produce is used as a measurement.

## STEP 4: Contracts

| Method | Path | Who | Result |
|---|---|---|---|
| GET | `/api/tracking/drivers` | staff or viewer | last known position of every located driver, newest first |
| GET | `/api/tracking/drivers/{id}` | staff or viewer | one driver's position, 404 if unknown |
| GET | `/api/tracking/stream` | staff or viewer | `text/event-stream`: `hello`, `driver-moved`, `order-status`, `delivery-delayed`, `route-recalculated`, `heartbeat` |
| GET | `/api/tracking/stream/clients` | staff | how many dashboards are connected |
| POST | `/api/tracking/sweep` | ADMIN | runs the check now: `{driversChecked, delaysReported, routesRecalculated, failures, durationMillis}` |
| POST | `/api/tracking/positions/rebuild` | ADMIN | refills the read model from the database |
| GET | `/api/simulation/status` | staff or viewer | which simulators are running, and a `[SIMULATION]` note when any is |

`LivePosition`: `driverId, latitude, longitude, at, source`.

New events: `DELIVERY_DELAYED` (payload `orderId, code, driverId, lateBySeconds, dueBy, predictedArrival, stopsRemaining, sequenceAlgorithm, graphVersion, predictedAt`) and `ROUTE_RECALCULATED` (payload `driverId, reason, previousDurationSeconds, durationSeconds, visitingOrderChanged, stopSequence, graphVersion, algorithm`).

Settings: `smartroute.tracking.*` (`enabled`, `sweep-interval` 15 s, `late-threshold` 60 s, `late-growth` 5 m, `recalculate-shift` 0.1, `heartbeat` 20 s, `position-ttl` 1 h), `smartroute.simulation.*` (`drivers`, `traffic`, `tick` 2 s, `speed-kph` 30, `traffic-tick` 30 s, `traffic-segments` 40, `max-multiplier` 4, `seed`), and `spring.task.scheduling.pool.size` (default 4 — see STEP 6).

## STEP 5: Tests (370 total, 36 new)
- `LivePositionsTest` (7): stores and reads a position with its source; an older position does not replace a newer one; a newer one does; without a Redis entry it falls back to the database; an unknown driver has no position; rebuild refills the cache; `all()` is newest first and covers drivers missing from the cache.
- `DeliveryWatchTest` (11): a delivery that cannot make its window raises exactly one alert, and the second sweep stays quiet; one that can raises nothing; an order with no window cannot be late; a delay that gets much worse is reported again; completing a delivery clears its alert; the first sweep stores a route without calling it a recalculation; traffic that changes a route's duration is reported as one, with the reason; **a traffic change alone reports a delivery that can no longer make its window** (FR-22, nothing calls the sweep); an empty system does nothing; a driver with no position is skipped without stopping the sweep; an admin can run the sweep through the API.
- `LiveStreamApiTest` (8): a connected client receives the published frames (and the greeting first); a client that is gone is dropped; the heartbeat reaches an idle stream; the stream is staff-and-viewer only; positions are readable and say where they came from; an unknown driver is 404; the administration endpoints are admin-only; the simulation status says nothing is simulated by default.
- `SimulatorTest` (8): a simulated driver moves towards the drop at roughly the configured speed; the position is labelled `SIMULATION` in the outbox payload; a driver with no deliveries is not moved; moving everyone skips a driver without a position; `advance` interpolates inside a segment and stops at the end of the path; the traffic simulator only produces multipliers the network accepts; it is reproducible for a seed; a jammed network makes the same trip slower.
- `KafkaEventFlowTest` (+2, real broker): a location event reaches the live read model and the API shows its source; an older location event does not move the driver backwards.
- `RoadNetworkProviderTest` (+1): a traffic change publishes an event for listeners to react to.

## STEP 6: Measurements
Full output: [docs/benchmarks/phase-10-live-tracking.txt](../benchmarks/phase-10-live-tracking.txt). Seeded demo data, 40 orders auto-dispatched, both simulators on, docker compose on the 4-vCPU VM. One client watching the stream for 60 s.

| Configuration | position recorded → frame read (p50) | p95 | worst |
|---|---|---|---|
| 1 scheduler thread, relay 500 ms | 508 ms | 1931 ms | 2135 ms |
| 1 scheduler thread, relay 50 ms | 613 ms | 5466 ms | 29 216 ms |
| 4 scheduler threads, relay 500 ms | 530 ms | 878 ms | 3266 ms |
| 4 scheduler threads, relay 50 ms | 122 ms | 219 ms | 738 ms |

A sweep over 48 drivers with active deliveries: **1.6–2.6 s**, with nothing newly late.

**Reading these honestly:**
- **The second row is a bug this measurement found.** Making the relay ten times faster made the live map *worse* (p95 5.5 s, worst case 29 s). Spring's scheduler runs a single thread by default and everything scheduled shares it: the outbox relay, the 2-second tracking sweep, the driver simulator and the heartbeat. The faster relay only competed for that thread more often. With `spring.task.scheduling.pool.size=4` the same configuration gives p50 122 ms / p95 219 ms, and the default interval improves from p95 1.9 s to 0.9 s. Nothing in the test suite would have shown this; it took watching the real thing.
- The remaining delay is the relay's polling interval, as in Phase 9. Half a second from position to pixel is fine for a delivery map and is **not** "real-time" in any strict sense; the number is a setting, and the honest description is "about half a second behind, by choice".
- The sweep costs ~35–55 ms per driver because it computes a cost matrix and a visiting order for each one (Phase 8 measured the matrix as the expensive part). At the 15 s interval that is roughly 15 % of one core, continuously, for 48 drivers. **It does not scale to a few hundred active drivers on one instance**, and the fix is a smaller sweep (only drivers whose orders or roads changed), not a faster one.
- 16–19 frames per second is the simulator's rate, not a measured limit. One client on the same machine: this says nothing about many dashboards, which belongs in Phase 13.

## STEP 7: Review notes
- **A read-only transaction silently dropped every simulated position.** The new `updateLocation(..., LocationSource)` overload had no `@Transactional`, and `DriverService` is `@Transactional(readOnly = true)` by default. Hibernate skipped the flush, so neither the position nor its outbox row was written, and the simulator reported success. `SimulatorTest` caught it (the position had not moved and the payload said `API`); both overloads are now annotated. The same family of mistake as Phase 9's self-call: an annotation that looks present because it is on the method next door.
- **The sweep ran twice in the first version of two tests.** Changing traffic triggers a sweep (FR-22), so the explicit `watch.sweep(...)` in the test found nothing left to report and the assertion failed. That was the feature working; the tests now assert that the traffic change alone is enough, which is a better test than the one I first wrote.
- **The timer and the traffic reaction had to be separable.** With both in one component, switching the scheduled sweep off in tests would also have switched off FR-22. They are now two components, and the test suite keeps the traffic reaction on while running the sweep by hand.
- Deliberate limitation: one instance. The relay, the sweep and the simulators all assume a single application, and two instances would duplicate the sweep's work (the alerts would stay correct — the row is a primary key — but the events could double).

## Interview questions
1. Why do positions reach Redis by consuming Kafka instead of being written there when the API call happens?
2. The Kafka key is the driver id, so one driver's positions stay in order. Why does the code still compare timestamps before storing a position?
3. The event-log consumer deduplicates with a database row; the live-feed consumer does not. Justify the difference.
4. A dashboard on a bad connection cannot keep up with the frames. What are the options, and what does this implementation choose?
5. Why SSE here rather than WebSockets? When would that answer change?
6. What exactly does "this delivery is predicted to be late" mean in this system, and what does it not mean?
7. Why is there a `delivery_alert` table instead of just publishing an event whenever a stop is late?
8. Making the outbox relay ten times faster made the live map's p95 five times worse. How can that happen, and how would you find it?
9. The sweep costs about 50 ms per driver. At what fleet size does the 15 s interval stop working, and what would you change first?
10. Everything the simulators produce is labelled `SIMULATION`. Why is that worth the extra field, and where would you want the label to be visible?
11. Two instances of this application run the same tracking sweep. What is still correct, and what is not?
12. A traffic change triggers an immediate sweep. What stops a flood of traffic updates from flooding the system with recalculations?
