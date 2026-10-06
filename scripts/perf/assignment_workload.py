#!/usr/bin/env python3
"""Compare auto-dispatch weightings on the seeded demo data.

Runs the greedy auto-dispatcher twice on the same data: once with the configured weights, once with
ETA only (the "always the nearest driver" baseline). Between the runs every assigned order is put back
in the queue, so both runs start from the same state. Prints the real numbers it measured; nothing here
is estimated.

Usage: BASE_URL=http://localhost:8080 ADMIN_PASSWORD=... python3 scripts/perf/assignment_workload.py
"""
import json
import os
import statistics
import urllib.request

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
EMAIL = os.environ.get("ADMIN_EMAIL", "admin@smartroute.local")
PASSWORD = os.environ["ADMIN_PASSWORD"]
LIMIT = int(os.environ.get("LIMIT", "400"))

DEFAULT_WEIGHTS = {"etaWeight": 0.6, "workloadWeight": 0.25, "capacityWeight": 0.15}
ETA_ONLY = {"etaWeight": 1.0, "workloadWeight": 0.0, "capacityWeight": 0.0}
LIMITS = {"etaCapSeconds": 1800, "searchRadiusMeters": 5000, "maxCandidates": 50, "maxActiveDeliveries": 8}


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request) as response:
        text = response.read().decode()
    return json.loads(text) if text else None


def login():
    return call("POST", "/api/auth/login", body={"email": EMAIL, "password": PASSWORD})["accessToken"]


def page(token, path):
    """Every item of a paged endpoint."""
    items, p = [], 0
    while True:
        body = call("GET", f"{path}&page={p}&size=100", token)
        items.extend(body["content"])
        if p >= body["totalPages"] - 1:
            return items
        p += 1


def reset(token):
    """Put every assigned order back in the queue (also releases the drivers' capacity)."""
    for order in page(token, "/api/orders?status=ASSIGNED"):
        call("POST", f"/api/orders/{order['id']}/unassign", token, {"reason": "experiment reset"})


def run(token, weights, label):
    call("PUT", "/api/admin/assignment-config", token, {**weights, **LIMITS})
    result = call("POST", f"/api/assignments/auto?limit={LIMIT}", token)
    loads = [d["activeDeliveryCount"] for d in page(token, "/api/drivers?") if d["status"] != "OFFLINE"]
    busy = [n for n in loads if n > 0]
    etas = [a["etaSeconds"] for a in result["assigned"]]
    print(f"\n{label}")
    print(f"  assigned                 {result['assignedCount']} of {result['waitingAtStart']} waiting")
    print(f"  not assigned             {result['notAssignedCount']}")
    print(f"  run time                 {result['durationMillis']} ms")
    print(f"  drivers used             {len(busy)} of {len(loads)} on shift")
    print(f"  deliveries per driver    max {max(loads)}, mean {statistics.mean(loads):.2f}, "
          f"stdev {statistics.pstdev(loads):.2f}")
    print(f"  pickup ETA (minutes)     mean {statistics.mean(etas) / 60:.1f}, "
          f"median {statistics.median(etas) / 60:.1f}, max {max(etas) / 60:.1f}")
    return {"label": label, "assigned": result["assignedCount"], "drivers_used": len(busy),
            "stdev": statistics.pstdev(loads), "max_load": max(loads),
            "mean_eta_min": statistics.mean(etas) / 60}


def main():
    token = login()
    reset(token)
    first = run(token, DEFAULT_WEIGHTS, "Configured weights: ETA 0.6, workload 0.25, capacity fit 0.15")
    reset(token)
    second = run(token, ETA_ONLY, "ETA only (nearest-driver baseline): ETA 1.0, workload 0, capacity fit 0")
    call("PUT", "/api/admin/assignment-config", token, {**DEFAULT_WEIGHTS, **LIMITS})
    print("\nDifference (configured vs ETA only): "
          f"stdev {first['stdev']:.2f} vs {second['stdev']:.2f}, "
          f"busiest driver {first['max_load']} vs {second['max_load']} deliveries, "
          f"mean pickup ETA {first['mean_eta_min']:.1f} vs {second['mean_eta_min']:.1f} min")


if __name__ == "__main__":
    main()
