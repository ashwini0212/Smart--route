#!/usr/bin/env python3
"""Throughput and latency of one endpoint under several concurrent clients.

Every other script here uses a single sequential client, which answers "how long does one request take"
and says nothing about what happens when twenty arrive at once. This one runs N threads, each with its own
keep-alive connection, for a fixed duration, and reports what the server managed: requests per second,
the latency percentiles, and anything that came back as an error.

Usage:
    DEMO_USER_PASSWORD=... python3 scripts/perf/load.py [clients] [seconds] [workload]

    clients   concurrent clients, default 8
    seconds   how long to drive them, default 20
    workload  route-fresh (a new trip every request: A* every time)
              route-cached (the same trip: Redis hit, still writes a history row)
              orders (GET /api/orders?status=CREATED, page 0)
              analytics (GET /api/analytics/overview?days=7)
              mixed (default: a third each of route-fresh, route-cached and orders)

The machine this runs on has 4 cores and is also running PostgreSQL, Redis and Kafka, so these numbers
describe this box under this load and nothing more. What they are good for is the shape: where latency
starts to climb, and whether throughput stops rising when the clients are added.
"""

import http.client
import json
import os
import random
import statistics
import sys
import threading
import time

HOST = os.environ.get("HOST") or "localhost"
PORT = int(os.environ.get("PORT") or "8080")
EMAIL = os.environ.get("DEMO_EMAIL") or "admin@smartroute.local"
PASSWORD = os.environ["DEMO_USER_PASSWORD"]
CLIENTS = int(sys.argv[1]) if len(sys.argv) > 1 else 8
SECONDS = float(sys.argv[2]) if len(sys.argv) > 2 else 20
WORKLOAD = sys.argv[3] if len(sys.argv) > 3 else "mixed"

FIXED_TRIP = {"from": {"latitude": 12.9800, "longitude": 77.6000}, "to": {"latitude": 13.0200, "longitude": 77.6500}}


def login() -> str:
    conn = http.client.HTTPConnection(HOST, PORT)
    conn.request("POST", "/api/auth/login", body=json.dumps({"email": EMAIL, "password": PASSWORD}),
                 headers={"Content-Type": "application/json"})
    response = conn.getresponse()
    body = response.read()
    if response.status != 200:
        sys.exit(f"login failed: {response.status} {body[:200]!r}")
    return json.loads(body)["accessToken"]


def request_for(workload: str, rnd: random.Random):
    """Returns (method, path, body) for one request of this workload."""
    def point():
        return {"latitude": rnd.uniform(12.965, 13.075), "longitude": rnd.uniform(77.585, 77.700)}

    if workload == "mixed":
        workload = rnd.choice(["route-fresh", "route-cached", "orders"])
    if workload == "route-fresh":
        return "POST", "/api/routes/fastest", {"from": point(), "to": point()}
    if workload == "route-cached":
        return "POST", "/api/routes/fastest", FIXED_TRIP
    if workload == "orders":
        return "GET", "/api/orders?status=CREATED&page=0&size=20", None
    if workload == "analytics":
        return "GET", "/api/analytics/overview?days=7", None
    sys.exit(f"unknown workload {workload!r}")


def drive(token: str, stop_at: float, seed: int, latencies: list, statuses: dict, lock: threading.Lock):
    conn = http.client.HTTPConnection(HOST, PORT)
    rnd = random.Random(seed)
    mine: list[float] = []
    # Keys are HTTP status codes, or a string describing a client-side failure.
    mine_statuses: dict[int | str, int] = {}
    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + token}
    while time.perf_counter() < stop_at:
        method, path, body = request_for(WORKLOAD, rnd)
        started = time.perf_counter()
        try:
            conn.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
            response = conn.getresponse()
            response.read()
            status = response.status
        except (http.client.HTTPException, OSError) as error:
            conn.close()
            conn = http.client.HTTPConnection(HOST, PORT)
            status = f"client error: {type(error).__name__}"
        mine.append((time.perf_counter() - started) * 1000)
        mine_statuses[status] = mine_statuses.get(status, 0) + 1
    conn.close()  # one socket per client thread, and a 64-client run holds 64 of them
    with lock:
        latencies.extend(mine)
        for status, count in mine_statuses.items():
            statuses[status] = statuses.get(status, 0) + count


def main() -> None:
    token = login()
    latencies: list[float] = []
    statuses: dict = {}
    lock = threading.Lock()
    stop_at = time.perf_counter() + SECONDS
    started = time.perf_counter()
    threads = [threading.Thread(target=drive, args=(token, stop_at, 100 + i, latencies, statuses, lock))
               for i in range(CLIENTS)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    elapsed = time.perf_counter() - started

    if not latencies:
        # Reporting zero requests is a result; an IndexError three lines down is not.
        print(f"{WORKLOAD}: {CLIENTS} clients for {elapsed:.1f} s")
        print(f"  requests            0 — nothing completed. Responses: "
              f"{dict(sorted(statuses.items(), key=lambda kv: str(kv[0])))}")
        print("  Is the server running on "
              f"{HOST}:{PORT}?")
        return

    latencies.sort()
    def pct(p):
        return latencies[min(len(latencies) - 1, int(p / 100 * len(latencies)))]

    print(f"{WORKLOAD}: {CLIENTS} clients for {elapsed:.1f} s")
    print(f"  requests            {len(latencies)}  ({len(latencies) / elapsed:.1f}/s)")
    print(f"  latency             p50 {pct(50):.1f} ms  p95 {pct(95):.1f} ms  p99 {pct(99):.1f} ms  "
          f"max {latencies[-1]:.1f} ms")
    print(f"  responses           {dict(sorted(statuses.items(), key=lambda kv: str(kv[0])))}")
    print(f"  mean latency        {statistics.mean(latencies):.1f} ms")


if __name__ == "__main__":
    main()
