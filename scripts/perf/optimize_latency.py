#!/usr/bin/env python3
"""Measure POST /api/routes/optimize end to end for several stop counts.

Sends real requests to a running SmartRoute instance with random stops inside the loaded network's
bounds, and reports the latencies it measured together with the server's own sequencing time, so the
share spent ordering stops can be told apart from the share spent building the cost matrix.

Usage: ADMIN_PASSWORD=... python3 scripts/perf/optimize_latency.py [requests-per-size]
"""
import json
import os
import random
import statistics
import sys
import time
import urllib.request

BASE = os.environ.get("BASE_URL") or "http://localhost:8080"
EMAIL = os.environ.get("ADMIN_EMAIL") or "admin@smartroute.local"
PASSWORD = os.environ["ADMIN_PASSWORD"]
PER_SIZE = int(sys.argv[1]) if len(sys.argv) > 1 else 15
SIZES = [3, 5, 8, 10, 12, 14, 16, 20]


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request) as response:
        text = response.read().decode()
    return json.loads(text) if text else None


def main():
    token = call("POST", "/api/auth/login", body={"email": EMAIL, "password": PASSWORD})["accessToken"]
    network = call("GET", "/api/routing/network", token)
    bounds = network["bounds"]
    random.seed(42)

    def point():
        return {"latitude": random.uniform(bounds["minLatitude"], bounds["maxLatitude"]),
                "longitude": random.uniform(bounds["minLongitude"], bounds["maxLongitude"])}

    print(f"Network: {network['source']}, {network['nodes']} nodes, {network['edges']} edges")
    print(f"{PER_SIZE} requests per size, one sequential client\n")
    print(f"{'stops':>5} {'strategy':<10} {'p50 ms':>8} {'p95 ms':>8} {'sequencing ms (server)':>24}")
    for stops in SIZES:
        for strategy in ("AUTO", "HEURISTIC"):
            latencies, sequencing, optimal = [], [], None
            for _ in range(PER_SIZE):
                body = {"start": point(), "stops": [{"label": f"S{i}", "location": point()} for i in range(stops)],
                        "mode": "FASTEST", "strategy": strategy}
                started = time.perf_counter()
                try:
                    result = call("POST", "/api/routes/optimize", token, body)
                except urllib.error.HTTPError as error:
                    if error.code == 429:
                        time.sleep(3)
                        continue
                    raise
                latencies.append((time.perf_counter() - started) * 1000)
                sequencing.append(result["sequencingMillis"])
                optimal = result["optimal"]
            if not latencies:
                continue
            latencies.sort()
            p95 = latencies[int(len(latencies) * 0.95) - 1]
            label = f"{strategy}{' (exact)' if optimal else ''}"
            print(f"{stops:>5} {label:<10} {statistics.median(latencies):>8.1f} {p95:>8.1f} "
                  f"{statistics.median(sequencing):>24.1f}")


if __name__ == "__main__":
    main()
