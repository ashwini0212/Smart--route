# Measurement scripts

Every number in the README's Performance section and in the phase documents came from one of these, or from
the JMH suite in `backend/benchmarks`. Each script prints the conditions it ran under; raw output is kept in
`docs/benchmarks/`.

| Script | Answers | Needs |
|---|---|---|
| `overhead.py` | Where the milliseconds in one request go: HTTP, authentication, a read, the cache, A* | the stack running |
| `route_latency.py` | The route endpoint, computed against cached, one sequential client | the stack running |
| `optimize_latency.py` | Multi-stop ordering from 3 to 20 stops, exact against heuristic | the stack running |
| `assignment_workload.py` | Auto-dispatch: how the configured weights spread work against ETA only | the stack, seeded data (**changes data**) |
| `event_latency.py` | Commit to readable: the outbox relay, Kafka and the consumer | the stack with Kafka (**creates orders**) |
| `live_latency.py` | A driver position to a dashboard frame over SSE | the stack with `SIMULATE_DRIVERS=true` |
| `load.py` | Throughput and latency under N concurrent clients | the stack running |
| `db_queries.py` | What PostgreSQL does for the 20 hottest queries, with plans and scans | PostgreSQL only |

## Running them

```bash
set -a; . ./.env; set +a                 # POSTGRES_PASSWORD, DEMO_USER_PASSWORD, …
export ADMIN_PASSWORD="$DEMO_USER_PASSWORD"   # the seeded admin, unless you set ADMIN_PASSWORD yourself

python3 scripts/perf/db_queries.py                  # the database as it is
python3 scripts/perf/db_queries.py --scale 100000   # and at 100k orders, inserted and rolled back
python3 scripts/perf/overhead.py 200
python3 scripts/perf/route_latency.py "$DEMO_USER_PASSWORD"
python3 scripts/perf/optimize_latency.py 12
LIMIT=100 python3 scripts/perf/assignment_workload.py
ORDERS=40 python3 scripts/perf/event_latency.py
SECONDS=60 python3 scripts/perf/live_latency.py     # with the driver simulator on
python3 scripts/perf/load.py 8 15 mixed
```

## Reading what they print

- **Stop the simulators before measuring anything but the live stream.** With both on, the same database
  queries measured up to 20× slower: that is a few hundred writes a second competing for the same four
  cores, not the cost of the query.
- **Warm the JVM.** The first run after a restart measures JIT compilation. In Phase 13 a cold JVM made the
  route cache look like it saved 7 ms per request; warm, it saves 3.
- **`db_queries.py --scale` leaves dead rows behind.** The transaction is rolled back, so no rows are
  visible afterwards, but the pages are still there until autovacuum runs. Measure the real database before
  the scaled run, or wait.
- **`load.py` is not a load-testing framework.** No ramp-up, no think time, no correction for coordinated
  omission, and it runs on the same cores as the database it is loading. Read it for the shape across client
  counts, not as a capacity number: at one client it reports higher latency than `route_latency.py` does for
  the same endpoint, because threads and per-request body construction are on the client's side of the clock.
- **Nothing here invents a number.** If a script cannot measure something it says so rather than estimating,
  and the ones that change data (`assignment_workload.py`, `event_latency.py`) say that too.
