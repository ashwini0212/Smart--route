#!/usr/bin/env python3
"""Where the milliseconds in a request actually go, by measuring one layer at a time.

A route that comes back from the cache in 11 ms is not 11 ms of routing. This script walks up from an
endpoint that does nothing to one that does everything, on the same host over the same keep-alive
connection, so each step's cost is the difference between two measurements rather than a guess:

    /actuator/health/liveness HTTP, Tomcat, the filter chain. No authentication, no database. (Plain
                              /actuator/health is not the floor: it checks PostgreSQL, Redis and Kafka, and
                              measured 6 ms here — slower than endpoints that do real work.)
    /api/simulation/status    + the security filter chain and JWT verification.
    /api/warehouses           + a small JPA read and its JSON.
    /api/routes/fastest       + Redis cache read, 3 KB of geometry, and the history row this endpoint
       (repeated, cached)       writes for every request, cache hit or not.
    /api/routes/fastest       + A* over the road graph.
       (fresh coordinates)

Usage: DEMO_USER_PASSWORD=... python3 scripts/perf/overhead.py [requests-per-step]

Everything it prints was measured in this run. The client is Python's http.client, so a little of the
difference is the client's own overhead; the liveness probe measures that together with Tomcat's.

One caveat on the cached row: it repeats a single route, so its Redis key and its 3 KB of JSON stay hot.
scripts/perf/route_latency.py repeats 300 different routes instead, and measures ~11 ms for the same
endpoint; that is the number to quote for the endpoint, and this one for the layer comparison.
"""

import http.client
import json
import os
import random
import statistics
import sys
import time

BASE_HOST = os.environ.get("HOST") or "localhost"
BASE_PORT = int(os.environ.get("PORT") or "8080")
EMAIL = os.environ.get("DEMO_EMAIL") or "admin@smartroute.local"
PASSWORD = os.environ["DEMO_USER_PASSWORD"]
N = int(sys.argv[1]) if len(sys.argv) > 1 else 200

conn = http.client.HTTPConnection(BASE_HOST, BASE_PORT)


def call(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    start = time.perf_counter()
    conn.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
    response = conn.getresponse()
    data = response.read()
    return response.status, data, (time.perf_counter() - start) * 1000


def measure(label, make_request, count=N):
    times = []
    for _ in range(10):  # warm up: first requests include class loading and connection setup
        make_request()
    for _ in range(count):
        status, data, ms = make_request()
        if status >= 400:
            sys.exit(f"{label}: HTTP {status} {data[:200]!r}")
        times.append(ms)
    times.sort()
    return label, statistics.median(times), times[int(0.95 * len(times))], statistics.mean(times)


status, data, _ = call("POST", "/api/auth/login", {"email": EMAIL, "password": PASSWORD})
if status != 200:
    sys.exit(f"login failed: {status} {data[:200]!r}")
token = json.loads(data)["accessToken"]

# Seeded from the clock: with a fixed seed, the "computed" step of a second run would be served from the
# Redis cache this script's first run filled, and would measure the line above it instead.
seed = int(os.environ.get("SEED", time.time()))
rnd = random.Random(seed)


def random_pair():
    def point():
        return {"latitude": rnd.uniform(12.965, 13.075), "longitude": rnd.uniform(77.585, 77.700)}
    return {"from": point(), "to": point()}


fixed = random_pair()
call("POST", "/api/routes/fastest", fixed, token)  # make sure the cached case is actually cached

rows = [
    measure("liveness (no auth, no database)", lambda: call("GET", "/actuator/health/liveness")),
    measure("simulation status (auth, no database)", lambda: call("GET", "/api/simulation/status", token=token)),
    measure("warehouses (auth + small read)", lambda: call("GET", "/api/warehouses", token=token)),
    measure("route, cached (+ Redis, geometry, history row)",
            lambda: call("POST", "/api/routes/fastest", fixed, token)),
    measure("route, computed (+ A*)", lambda: call("POST", "/api/routes/fastest", random_pair(), token)),
]

print(f"{N} requests per step after 10 warm-ups, one keep-alive connection, milliseconds. Trip seed {seed}.\n")
print(f"{'step':<48}{'p50':>8}{'p95':>8}{'mean':>8}   step cost (p50)")
previous = None
for label, p50, p95, mean in rows:
    delta = "" if previous is None else f"+{p50 - previous:.2f} ms"
    print(f"{label:<48}{p50:>7.2f}ms{p95:>7.2f}ms{mean:>7.2f}ms   {delta}")
    previous = p50
print("\nThe step cost column is the difference from the line above, which is only meaningful because every")
print("line adds work to the one before it. A negative or tiny step means the layer costs less than the noise.")
