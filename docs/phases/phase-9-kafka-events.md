# Phase 9: Domain events on Kafka — outbox, idempotent consumers, retries, dead letters

## STEP 1: What we built

| Piece | Purpose |
|---|---|
| `V5__events.sql` | `outbox_event` (events waiting to be sent), `processed_event` (what each consumer group has handled), `system_event` (the readable stream) |
| `events.DomainEvents` | How the rest of the app publishes: one call, inside the transaction that made the change |
| `events.OrderEventPublisher` | Turns in-process Spring events (order status, driver location) into outbox rows |
| `events.OutboxRelay` + `OutboxRelayScheduler` | Sends unpublished rows to Kafka oldest-first and stamps them |
| `events.EventEnvelope` + `EventEnvelopeReader` | What goes on the wire, and strict parsing of it |
| `events.KafkaConfig` | Topics (3 partitions + a 1-partition `.DLT` each), blocking retries with exponential back-off, dead-letter publishing |
| `events.EventConsumers` + `ProcessedEvents` | Exactly-once handling of an at-least-once stream |
| `events.EventLogListener` | The first consumer: records every event in `system_event` |
| `events.EventController` | `GET /api/events` (the stream), `GET /api/events/outbox` (is the relay keeping up?) |
| `events.OutboxMaintenance` | Deletes published rows older than the retention window (default 7 days) |
| `scripts/perf/event_latency.py` | The latency measurement below, reproducible |

## STEP 2: Architecture

```
POST /api/orders                      ── one transaction ──────────────────────────────┐
   │                                                                                   │
   ├─ INSERT delivery_order                                                             │
   ├─ INSERT order_status_change                                                        │
   ├─ publishEvent(OrderStatusChangedEvent)  → OrderEventPublisher (synchronous)        │
   │                                            └─ INSERT outbox_event                  │
   └─ COMMIT  ◄── the change and its event become true at the same instant ─────────────┘

OutboxRelayScheduler every 500 ms
   │
   ▼
OutboxRelay.relayOnce()  (@Transactional)
   SELECT ... WHERE published_at IS NULL ORDER BY created_at, id LIMIT 100      partial index
   for each row:  kafka.send(topic, key = aggregateId, envelope).get()          awaited
                  row.markPublished(now)                                        stamped on commit
                  (a failure records the error, stops the batch, and the next run retries)

Kafka  smartroute.order-created / order-assigned / delivery-started / … (3 partitions each)
   │
   ▼
EventLogListener (group "event-log")
   reader.read(json)                        unknown type or newer version → IllegalArgumentException
   consumers.handleOnce(group, envelope):
        ┌ one transaction ────────────────────────────────┐
        │ INSERT processed_event (group, eventId)         │  duplicate → PK violation → rollback
        │ INSERT system_event                             │
        └─────────────────────────────────────────────────┘
   failure → back-off 200 ms, 400 ms, 800 ms (4 attempts) → smartroute.<topic>.DLT
```

## STEP 3: Design decisions explained

### 3.1 Why an outbox instead of sending from the service
A `kafkaTemplate.send` inside a transaction has two ways to lie. If the send succeeds and the transaction then rolls back, the rest of the system hears about an order that does not exist. If the transaction commits and the send fails, the change happened and nobody knows. There is no ordering of the two calls that fixes this, because they are two different systems.

Writing the event to the same database as the change makes them one atomic fact, and a separate relay deals with Kafka afterwards. `OutboxTest.anEventIsRolledBackWithTheChangeItDescribes` is the property stated as a test; `appendingOutsideATransactionFails` keeps it true, because `DomainEvents.append` is `Propagation.MANDATORY` — a caller with no transaction gets an exception rather than an event that commits on its own.

### 3.2 At-least-once, and what pays for it
The relay sends, then stamps `published_at`. A crash in between re-sends the event on the next run. That is deliberate: the alternative (stamp first, then send) loses events, and losing an event is worse than seeing it twice. So every consumer has to be able to see the same event twice without doing the work twice.

### 3.3 Idempotency by insert, not by lookup
The obvious consumer check is "have I already handled this event id?" followed by the work. Two instances, or two threads, can both pass the check before either finishes. Instead the consumer *inserts* `(consumer_group, event_id)` in the same transaction as its work: the primary key makes the second insert fail, the transaction rolls back, and the repeated work leaves nothing behind. The duplicate is recognised outside the transaction boundary (`EventConsumers.handleOnce`) because a constraint violation marks its transaction rollback-only; catching it inside would turn the commit into a confusing "transaction silently rolled back".

`EventConsumersIdempotencyTest.twoThreadsDeliveringAtOnceStillDoTheWorkOnce` runs four threads on one event and asserts exactly one did the work. `aFailingConsumerLeavesNoClaimBehindSoTheRetryCanWork` covers the other side: a failure must not leave a marker, or the retry would be mistaken for a duplicate and the work would never run.

The marker is per consumer group, so a second consumer added later reads the whole log on its own and is not affected by what the first one has done.

### 3.4 Keys, partitions and order
The Kafka key is the aggregate id, so every event about one order lands on one partition and is delivered in the order it was written. The relay also awaits each send before starting the next one, which is what makes "created" reach the broker before "assigned" even on a fresh topic. That costs a round trip per event (the measurement below shows what that buys and costs); a batching relay would be faster and would need sequence numbers in the payload to keep order.

Three partitions per topic is a guess at a useful default, not a measured number: it spreads load across consumers while keeping per-order ordering. The partition count can be raised later but never lowered, which is why the topics are created by the application (`KafkaAdmin.NewTopics`) rather than auto-created with the broker default.

### 3.5 Topics by lifecycle step, not by aggregate
`smartroute.order-created`, `…order-assigned`, `…delivery-started`, `…delivery-completed`, `…delivery-delayed`, `…route-recalculated`, `…driver-location-updated`. Consumers subscribe to what they care about: a notification service wants completions, analytics wants everything. Two exceptions are deliberate: `ORDER_UNASSIGNED` shares the assignment topic (a consumer tracking who holds an order sees both, in order, keyed by the order), and `IN_TRANSIT` is published at all — it is a step inside a delivery that nothing outside acts on.

### 3.6 A versioned envelope, and refusing what we do not understand
Every record carries `eventId, type, version, aggregateType, aggregateId, occurredAt, correlationId, payload`. The reader rejects an unknown type and a version newer than it knows, as `IllegalArgumentException` — which the error handler treats as non-retryable, so it goes straight to the dead-letter topic. A consumer that quietly ignored what it did not understand would produce wrong analytics instead of an alert.

The envelope is assembled from the stored columns at send time rather than kept as one JSON blob, so adding an envelope field does not mean rewriting old rows.

### 3.7 Retries that keep order, then a dead letter
Retries are blocking: the listener waits (200 ms, 400 ms, 800 ms) and tries the same record again, so the partition's order is preserved while one record is being retried. After four attempts the record is published to `<topic>.DLT` with headers describing the failure, and the consumer moves on — otherwise one bad message stops every later event on that partition, which `laterEventsAboutOneOrderAreNotHeldUpByAnEarlierBadRecord` shows is not what happens.

### 3.8 Operational honesty: the outbox is observable
`GET /api/events/outbox` returns `pending` and `published`. A growing `pending` is the symptom of every relay problem (broker down, serialization error, a crashed scheduler), and it was how both bugs in STEP 7 were found. Published rows are kept for 7 days as the record of what was actually sent, then deleted hourly by `OutboxMaintenance`.

## STEP 4: Contracts

| Method | Path | Who | Result |
|---|---|---|---|
| GET | `/api/events` | staff or viewer | recorded events, newest first; filter by `eventType`, or by `aggregateType` + `aggregateId` |
| GET | `/api/events/outbox` | ADMIN | `{pending, published, at}` |

`SystemEventResponse`: `id, eventId, eventType, eventVersion, topic, aggregateType, aggregateId, summary, payload, correlationId, occurredAt, recordedAt`.

The envelope on the wire:

```json
{"eventId":"03bbae9c-…","type":"ORDER_CREATED","version":1,"aggregateType":"order","aggregateId":"602",
 "occurredAt":"2026-10-06T13:40:53.272925Z","correlationId":"475f0cd6-…",
 "payload":{"orderId":602,"code":"ORD-000602","status":"CREATED","previousStatus":null,"driverId":null,
            "warehouseId":3,"priority":"NORMAL","weightKg":8,"volumeM3":0.4,"reason":"Order created",
            "at":"2026-10-06T13:40:53.258421561Z"}}
```

Settings (`smartroute.events.*`): `enabled` (false turns publishing and consuming off; the outbox is still written, because that is part of the transaction), `relay-interval` (default 500 ms), `keep-published` (default 7 days). `EVENTS_RELAY_INTERVAL` is passed through by docker-compose.

## STEP 5: Tests (333 total, 27 new)
- `EventEnvelopeReaderTest` (7): every field read; unknown type rejected; newer version rejected; older version accepted; a missing envelope field rejected; a null `correlationId` allowed; nested payload values kept as JSON.
- `OutboxTest` (8, no broker): order creation writes one event with its payload, topic and key; the full lifecycle writes exactly four events and no `IN_TRANSIT` one; unassignment is its own event on the assignment topic; a driver location update is keyed by driver; an event is rolled back with the change it describes; appending outside a transaction fails; the outbox endpoint counts pending rows and is admin-only; retention keeps a row published now.
- `EventConsumersIdempotencyTest` (4): the second delivery does no work; another consumer group handles the same event on its own; four concurrent deliveries do the work once; a failing consumer leaves no claim behind.
- `KafkaEventFlowTest` (8, real Kafka in Testcontainers): an order becomes a record on Kafka (keyed, versioned envelope) and then a row in the event log, readable through the API; the scheduled run stamps what it sent; the same record twice is recorded once; an event no consumer understands goes to the dead-letter topic with failure headers; a poison record does not hold up the next event on the same key; the whole lifecycle is published in order; the application creates its topics with 3 partitions (and 1 for each `.DLT`); the stream is readable by staff and viewers only.

The test broker runs with `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`, like docker-compose, so a test fails if the application does not create its own topics.

## STEP 6: Measurements
Full output: [docs/benchmarks/phase-9-events.txt](../benchmarks/phase-9-events.txt). 50 orders created one at a time, docker compose on the 4-vCPU VM.

| From commit to readable in the event log | p50 | p95 | max |
|---|---|---|---|
| Relay interval 500 ms (the default) | 474 ms | 494 ms | 569 ms |
| Relay interval 50 ms, same code | 59 ms | 76 ms | 1058 ms |

**Reading these honestly:**
- Almost all of the 474 ms is waiting for the next relay run, not Kafka: the same pipeline delivers in 59 ms when the relay polls ten times as often. The outbox buys atomicity and pays for it in latency.
- This is not "real-time". An event is readable a few hundred milliseconds after the change, and that number is a configuration choice (`EVENTS_RELAY_INTERVAL`) traded against how often the database is asked a question whose answer is usually "nothing to do".
- The 1058 ms outlier at the 50 ms setting is one order in fifty; it did not appear at 500 ms, where such a wait hides inside the polling interval. One sequential client, so these numbers say nothing about throughput under load — that belongs in Phase 13.
- What would cut the delay without giving up the outbox: wake the relay on commit (a `@TransactionalEventListener(AFTER_COMMIT)` nudge) and keep the timer as the safety net. Not done here, because the timer alone is what makes the recovery path easy to reason about.

## STEP 7: Review notes
Four problems, two of which only showed up when the stack ran for real. They are worth writing down because the test suite was green for all four.

- **The topics were never created.** `KafkaConfig` declared a `List<NewTopic>` bean; `KafkaAdmin` only looks at `NewTopic` and `KafkaAdmin.NewTopics` beans, so nothing was created and every publish failed with `UNKNOWN_TOPIC_OR_PARTITION`. The tests passed because the Testcontainers broker auto-creates topics on first use. Fixed with `KafkaAdmin.NewTopics`; the test broker now has auto-creation off and a test asserts the partition counts, so this cannot pass again.
- **The relay published the same events forever.** `@Scheduled` sat on `OutboxRelay.publishPending()`, which called its own `@Transactional relayOnce()` — a self-call bypasses the proxy, so there was no transaction and `published_at` was never written. Kafka was receiving every pending event every 500 ms, and the consumers' deduplication hid it; only `GET /api/events/outbox` showing `pending: 2, published: 0` while the events were plainly arriving gave it away. The schedule now lives in `OutboxRelayScheduler`, which calls the relay through its proxy. Verified by removing `@Transactional` again and watching the new test fail.
- **Integral payload values became doubles.** `return node.isIntegralNumber() ? node.asLong() : node.asDouble();` — one ternary with a `long` and a `double` branch is promoted to `double`, so `"orderId":42` was parsed as `42.0`. Caught by `EventEnvelopeReaderTest`, which asserts the type and not just the value; fixed by writing the two branches as two returns.
- **`ORDER_CREATED` was never published.** `OrderService.create` wrote the history row directly instead of going through `recordChange`, so the first status change published no Spring event and the stream started at `ORDER_ASSIGNED`. Creation now uses the same path as every other change, and `OrderStatusChangedEvent` carries the warehouse and priority the event payload needs.

Two deliberate omissions: the relay is written for one instance (two would both publish, which the consumers survive but which deserves a lock or a leader before it matters), and nothing yet consumes these events except the log — notifications and analytics are later phases.

## Interview questions
1. A service writes to its database and then publishes to Kafka. Give two ways that goes wrong, and explain how an outbox removes both.
2. The relay sends, then stamps `published_at`. What happens if the process dies between the two? Why is that order of operations the right one?
3. Why does the consumer insert its "already handled" marker in the same transaction as the work, instead of checking first? What race does that close?
4. The duplicate is caught outside the transaction, not inside. What goes wrong if you catch it inside?
5. Why is the Kafka key the aggregate id? What ordering guarantee does that give you, and what does it *not* give you?
6. An event arrives with a `version` the consumer does not know. Why refuse it instead of reading the fields you recognise?
7. Retries here are blocking rather than re-queueing the record. What does blocking buy, and what is the cost if the failure lasts a minute?
8. What do you put in a dead-letter topic, and who is supposed to look at it? What happens to the partition while one record is being retried?
9. `GET /api/events/outbox` returns `pending` and `published`. What would a `pending` that keeps growing tell you, and what would you check first?
10. Events are readable ~470 ms after the change, and 59 ms if the relay polls ten times as often. Would you lower the interval, or change the design? What does each option cost?
11. Two instances of this application run the same relay. What breaks, what survives, and how would you fix it?
12. `smartroute.events.enabled=false` stops publishing but still writes the outbox. Why keep writing it?
