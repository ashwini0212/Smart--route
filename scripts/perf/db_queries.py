"""What the database actually does for the queries this application runs hottest.

Every query below is one the running system issues: the order list and its page count, the outbox relay's
poll, the event log, the tracking sweep's driver lookup, the assignment candidate read and the four
analytics aggregates. Each is run with EXPLAIN (ANALYZE, BUFFERS) several times; the script reports the
median execution time, the rows the planner expected against the rows it got, and every sequential scan,
because a sequential scan over a table that only grows is the thing that stops working later.

Usage:
    python3 scripts/perf/db_queries.py                      # measure the database as it is
    python3 scripts/perf/db_queries.py --scale 100000       # and again with N extra orders, rolled back
    python3 scripts/perf/db_queries.py --runs 7 --quiet     # more repeats, no plans printed

The --scale run inserts [SYNTHETIC] orders and history rows inside a transaction and rolls it back, so the
database is left exactly as it was. Nothing it generates is real: the addresses are a grid of coordinates
and the customer name is the literal string "Synthetic Customer". It exists to answer one question — which
of these queries stops being cheap when the tables are large — and its timings describe that synthetic
shape, not a production workload.

Requires the compose stack (or any PostgreSQL reachable through psql); pass --psql to point elsewhere.
"""

import argparse
import re
import statistics
import subprocess
import sys

DEFAULT_PSQL = "docker compose exec -T postgres psql -U smartroute -d smartroute"

ACTIVE = "'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT'"
WINDOW = "now() - interval '7 days'"

# (name, what it serves, sql)
QUERIES = [
    ("orders_page",
     "GET /api/orders?status=CREATED — the dispatcher's first screen",
     f"""SELECT * FROM delivery_order WHERE status = 'CREATED'
         ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 0"""),
    ("orders_page_count",
     "the count Spring Data issues for the same page, to render 'of N'",
     "SELECT count(*) FROM delivery_order WHERE status = 'CREATED'"),
    ("orders_page_deep",
     "page 36 of the same list — what OFFSET costs",
     f"""SELECT * FROM delivery_order WHERE status = 'CREATED'
         ORDER BY created_at DESC, id DESC LIMIT 20 OFFSET 700"""),
    ("order_history",
     "GET /api/orders/{id} — the status history of one order",
     "SELECT * FROM order_status_history WHERE order_id = :an_order ORDER BY changed_at"),
    ("driver_active_orders",
     "a driver's own deliveries, and the tracking sweep's per-driver read",
     f"""SELECT * FROM delivery_order WHERE driver_id = :a_driver
           AND status IN ({ACTIVE}) ORDER BY assigned_at, id"""),
    ("drivers_with_active_orders",
     "DeliveryWatch.sweep() — who has anything to deliver right now",
     f"""SELECT DISTINCT driver_id FROM delivery_order
         WHERE driver_id IS NOT NULL AND status IN ({ACTIVE}) ORDER BY driver_id"""),
    ("available_drivers_at_warehouse",
     "the assignment candidate read, before scoring",
     "SELECT * FROM driver WHERE status = 'AVAILABLE' AND home_warehouse_id = :a_warehouse"),
    ("outbox_relay_poll",
     "OutboxRelay, every 500 ms: the oldest unpublished events",
     "SELECT * FROM outbox_event WHERE published_at IS NULL ORDER BY created_at, id LIMIT 200"),
    ("outbox_count_pending",
     "the outbox_pending gauge and GET /api/events/outbox",
     "SELECT count(*) FROM outbox_event WHERE published_at IS NULL"),
    ("outbox_count_published",
     "what GET /api/events/outbox used to do; it now reports an estimate instead (Phase 13)",
     "SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL"),
    ("events_page",
     "GET /api/events — the recorded stream, newest first",
     "SELECT * FROM system_event ORDER BY occurred_at DESC, id DESC LIMIT 20"),
    ("events_page_count",
     "what a counted page over the log costs; /api/events returns a slice instead (Phase 13)",
     "SELECT count(*) FROM system_event"),
    ("events_by_aggregate",
     "GET /api/events?aggregateType=ORDER&aggregateId=… — one order's events",
     """SELECT * FROM system_event WHERE aggregate_type = 'ORDER'
        AND aggregate_id = :'an_order' ORDER BY occurred_at DESC, id DESC LIMIT 20"""),
    ("refresh_token_lookup",
     "every token refresh: find the presented token by its hash",
     "SELECT * FROM refresh_token WHERE token_hash = 'no-such-hash'"),
    ("analytics_status_counts",
     "/api/analytics/overview — orders created in the window by status",
     f"SELECT status, count(*) FROM delivery_order WHERE created_at >= {WINDOW} GROUP BY status"),
    ("analytics_punctuality",
     "/api/analytics/overview — on time against the delivery window",
     f"""SELECT count(*) FILTER (WHERE o.window_end IS NOT NULL) AS with_window,
                count(*) FILTER (WHERE o.window_end IS NOT NULL AND h.changed_at <= o.window_end) AS on_time
         FROM order_status_history h JOIN delivery_order o ON o.id = h.order_id
         WHERE h.to_status = 'DELIVERED' AND h.changed_at >= {WINDOW} AND h.changed_at <= now()"""),
    ("analytics_duration",
     "/api/analytics/overview — assignment to delivery, per delivery",
     f"""SELECT EXTRACT(EPOCH FROM (d.changed_at - a.changed_at)) / 60 AS minutes
         FROM order_status_history d
         JOIN LATERAL (SELECT changed_at FROM order_status_history
                       WHERE order_id = d.order_id AND to_status = 'ASSIGNED'
                       ORDER BY changed_at DESC LIMIT 1) a ON TRUE
         WHERE d.to_status = 'DELIVERED' AND d.changed_at >= {WINDOW} AND d.changed_at <= now()"""),
    ("analytics_throughput",
     "/api/analytics/throughput — completions per day",
     f"""SELECT (changed_at AT TIME ZONE 'UTC')::date AS day, to_status, count(*)
         FROM order_status_history WHERE changed_at >= {WINDOW}
           AND to_status IN ('DELIVERED', 'FAILED', 'CANCELLED') GROUP BY 1, 2"""),
    ("analytics_fleet",
     "/api/analytics/fleet — per-driver deliveries, the heaviest query here",
     f"""WITH completions AS (
             SELECT h.order_id, h.to_status, h.changed_at, o.window_end, o.driver_id,
                    (SELECT a.created_at FROM assignment a
                     WHERE a.order_id = h.order_id AND a.created_at <= h.changed_at
                     ORDER BY a.created_at DESC, a.id DESC LIMIT 1) AS assigned_at
             FROM order_status_history h JOIN delivery_order o ON o.id = h.order_id
             WHERE h.to_status IN ('DELIVERED', 'FAILED') AND h.changed_at >= {WINDOW}
               AND h.changed_at <= now() AND o.driver_id IS NOT NULL)
         SELECT d.id, d.code, count(*) FILTER (WHERE c.to_status = 'DELIVERED') AS delivered,
                percentile_cont(0.5) WITHIN GROUP (
                    ORDER BY EXTRACT(EPOCH FROM (c.changed_at - c.assigned_at)) / 60) AS median_minutes
         FROM completions c JOIN driver d ON d.id = c.driver_id
         GROUP BY d.id, d.code ORDER BY delivered DESC, d.code LIMIT 20"""),
    ("analytics_eta_accuracy",
     "/api/analytics/eta-accuracy — the predicted pickup time against what happened",
     f"""SELECT count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY actual - predicted)
         FROM (SELECT a.eta_seconds / 60.0 AS predicted,
                      EXTRACT(EPOCH FROM (h.changed_at - a.created_at)) / 60 AS actual
               FROM order_status_history h
               JOIN LATERAL (SELECT a.eta_seconds, a.created_at FROM assignment a
                             WHERE a.order_id = h.order_id AND a.created_at <= h.changed_at
                               AND a.eta_seconds IS NOT NULL
                             ORDER BY a.created_at DESC, a.id DESC LIMIT 1) a ON TRUE
               WHERE h.to_status = 'PICKED_UP' AND h.changed_at >= {WINDOW}
                 AND h.changed_at <= now()) pairs"""),
]

# Orders and their history, in the proportions the seeder produces: one CREATED row for every order and
# four more rows for the ones that were delivered, spread over a year so that an analytics window is a slice
# of the table and not all of it. Everything is fictional and the transaction is rolled back.
LOAD = """
INSERT INTO delivery_order (code, warehouse_id, customer_name, drop_address,
        drop_latitude, drop_longitude, priority, status, weight_kg, volume_m3, driver_id, assigned_at,
        window_end, created_at, updated_at)
SELECT 'SYN-' || g,
       (SELECT id FROM warehouse ORDER BY id LIMIT 1),
       'Synthetic Customer', g || ' Synthetic Road',
       12.90 + (g %% 900) * 0.0002, 77.50 + (g %% 700) * 0.0002,
       CASE g %% 3 WHEN 0 THEN 'HIGH' WHEN 1 THEN 'NORMAL' ELSE 'LOW' END,
       CASE WHEN g %% 10 < 6 THEN 'DELIVERED' WHEN g %% 10 < 8 THEN 'CREATED' ELSE 'IN_TRANSIT' END,
       10, 0.5,
       CASE WHEN g %% 10 < 6 OR g %% 10 >= 8 THEN (SELECT id FROM driver ORDER BY id LIMIT 1) + (g %% 120) END,
       now() - (g %% 8760) * interval '1 hour',
       now() - (g %% 8760) * interval '1 hour' + interval '3 hours',
       now() - (g %% 8760) * interval '1 hour' - interval '1 hour',
       now()
FROM generate_series(1, %(rows)d) g;

INSERT INTO order_status_history (order_id, from_status, to_status, changed_at)
SELECT o.id, NULL, 'CREATED', o.created_at FROM delivery_order o WHERE o.code LIKE 'SYN-%%';

INSERT INTO assignment (order_id, driver_id, method, eta_seconds, created_at, updated_at)
SELECT o.id, o.driver_id, 'AUTO', 600, o.assigned_at, o.assigned_at
FROM delivery_order o WHERE o.code LIKE 'SYN-%%' AND o.driver_id IS NOT NULL;

INSERT INTO order_status_history (order_id, from_status, to_status, changed_at)
SELECT o.id, 'CREATED', 'ASSIGNED', o.assigned_at
FROM delivery_order o WHERE o.code LIKE 'SYN-%%' AND o.driver_id IS NOT NULL;

INSERT INTO order_status_history (order_id, from_status, to_status, changed_at)
SELECT o.id, 'ASSIGNED', 'PICKED_UP', o.assigned_at + interval '20 minutes'
FROM delivery_order o WHERE o.code LIKE 'SYN-%%' AND o.driver_id IS NOT NULL;

INSERT INTO order_status_history (order_id, from_status, to_status, changed_at)
SELECT o.id, 'PICKED_UP', 'IN_TRANSIT', o.assigned_at + interval '25 minutes'
FROM delivery_order o WHERE o.code LIKE 'SYN-%%' AND o.driver_id IS NOT NULL;

INSERT INTO order_status_history (order_id, from_status, to_status, changed_at)
SELECT o.id, 'IN_TRANSIT', 'DELIVERED', o.assigned_at + interval '70 minutes'
FROM delivery_order o WHERE o.code LIKE 'SYN-%%' AND o.status = 'DELIVERED';

ANALYZE delivery_order;
ANALYZE order_status_history;
ANALYZE assignment;
"""

# Resolved before the measured queries run, so no query pays for finding its own parameters.
FIXTURE = """
SELECT max(id) AS an_order FROM delivery_order
\\gset
SELECT min(id) AS a_warehouse FROM warehouse
\\gset
SELECT driver_id AS a_driver FROM delivery_order WHERE driver_id IS NOT NULL
  AND status IN (%s) GROUP BY driver_id ORDER BY count(*) DESC LIMIT 1
\\gset
""" % ACTIVE

MARKER = "-- smartroute-marker "


def run_psql(psql: str, script: str) -> str:
    result = subprocess.run(psql, shell=True, input=script, text=True, capture_output=True)
    if result.returncode != 0:
        sys.exit(f"psql failed:\n{result.stderr.strip()}\n{result.stdout[-2000:]}")
    return result.stdout


def build_script(runs: int, scale: int, try_sql: str | None = None) -> str:
    parts = ["\\set ON_ERROR_STOP on", "\\pset pager off", "BEGIN;"]
    if scale:
        parts.append(LOAD % {"rows": scale})
    if try_sql:
        with open(try_sql) as handle:
            parts.append(handle.read())
        parts.append("ANALYZE delivery_order;\nANALYZE order_status_history;\nANALYZE assignment;")
    # Values the queries need, resolved once: a query should be timed, not its fixture.
    parts.append(FIXTURE)
    for name, _, sql in QUERIES:
        for attempt in range(runs + 1):  # the first run is a warm-up and is dropped
            parts.append(f"\\echo {MARKER}{name} {attempt}")
            parts.append(f"EXPLAIN (ANALYZE, BUFFERS) {sql.strip().rstrip(';')};")
    parts.append("ROLLBACK;")
    return "\n".join(parts) + "\n"


def parse(output: str, runs: int) -> dict:
    """Split psql's output on the markers and pull the timing and the plan out of each block."""
    blocks: dict[str, list[tuple[str, float]]] = {}
    current = None
    buffer: list[str] = []

    def flush():
        if current is None:
            return
        name, attempt = current
        text = "\n".join(buffer)
        match = re.search(r"Execution Time: ([\d.]+) ms", text)
        if match and attempt > 0:
            blocks.setdefault(name, []).append((text, float(match.group(1))))

    for line in output.splitlines():
        if line.startswith(MARKER):
            flush()
            name, attempt = line[len(MARKER):].split()
            current = (name, int(attempt))
            buffer = []
        else:
            buffer.append(line)
    flush()
    return blocks


def row_counts(psql: str, scale: int) -> str:
    script = ["BEGIN;"]
    if scale:
        script.append(LOAD % {"rows": scale})
    script.append("SELECT 'delivery_order ' || count(*) FROM delivery_order;")
    script.append("SELECT 'order_status_history ' || count(*) FROM order_status_history;")
    script.append("SELECT 'assignment ' || count(*) FROM assignment;")
    script.append("SELECT 'outbox_event ' || count(*) FROM outbox_event;")
    script.append("SELECT 'system_event ' || count(*) FROM system_event;")
    script.append("ROLLBACK;")
    out = run_psql(psql, "\n".join(script))
    return " · ".join(line.strip() for line in out.splitlines()
                      if re.match(r"^\s*(delivery_order|order_status_history|assignment|outbox_event|system_event) \d+", line))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--psql", default=DEFAULT_PSQL, help="command that pipes a script into psql")
    parser.add_argument("--runs", type=int, default=5, help="measured repeats per query (default 5)")
    parser.add_argument("--scale", type=int, default=0, help="extra synthetic orders, inserted and rolled back")
    parser.add_argument("--quiet", action="store_true", help="do not print the plans")
    parser.add_argument("--try-sql", help="file of DDL (an index candidate) applied inside the transaction"
                                          " before measuring, so a candidate can be judged and rolled back")
    args = parser.parse_args()

    print(f"Table sizes: {row_counts(args.psql, args.scale)}")
    if args.scale:
        print(f"[SYNTHETIC] The sizes above include {args.scale} generated orders and their history,")
        print("inserted inside a transaction that is rolled back. They are not real deliveries.")
    if args.try_sql:
        print(f"Candidate DDL applied (and rolled back) from {args.try_sql}:")
        with open(args.try_sql) as handle:
            for line in handle.read().splitlines():
                if line.strip():
                    print(f"    {line.strip()}")
    print(f"Each query: 1 warm-up + {args.runs} measured runs, EXPLAIN (ANALYZE, BUFFERS).\n")

    blocks = parse(run_psql(args.psql, build_script(args.runs, args.scale, args.try_sql)), args.runs)

    print(f"{'query':<34}{'median':>9}{'min':>9}{'max':>9}  scans")
    print("-" * 78)
    worst: list[tuple[float, str, str]] = []
    for name, description, _ in QUERIES:
        measurements = blocks.get(name)
        if not measurements:
            print(f"{name:<34}{'no plan captured':>27}")
            continue
        times = sorted(ms for _, ms in measurements)
        plan = measurements[0][0]
        seq = sorted({m.group(1) for m in re.finditer(r"Seq Scan on (\w+)", plan)})
        note = ("seq: " + ", ".join(seq)) if seq else ""
        print(f"{name:<34}{statistics.median(times):>8.2f}ms{times[0]:>8.2f}ms{times[-1]:>8.2f}ms  {note}")
        worst.append((statistics.median(times), name, description))

    print("\nSlowest first, with what each one serves:")
    for ms, name, description in sorted(worst, reverse=True)[:6]:
        print(f"  {ms:8.2f} ms  {name} — {description}")

    if not args.quiet:
        print("\n" + "=" * 78)
        for name, description, _ in QUERIES:
            measurements = blocks.get(name)
            if not measurements:
                continue
            print(f"\n--- {name}: {description}")
            print(measurements[0][0].strip())


if __name__ == "__main__":
    main()
