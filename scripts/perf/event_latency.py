#!/usr/bin/env python3
"""Measure how long a domain event takes to get from the database to the event log.

Creates orders through the API and, for each one, waits for its ORDER_CREATED event to appear on
GET /api/events. The reported delay is the event's own occurredAt (when the change was committed) to its
recordedAt (when the consumer stored it), which covers the relay's polling wait, the Kafka round trip and
the consumer's insert. It also prints how long the client had to wait, which includes its own polling.

Nothing here is estimated: every number comes from one of these runs.

Usage: BASE_URL=http://localhost:8080 ADMIN_PASSWORD=... python3 scripts/perf/event_latency.py
"""
import json
import os
import statistics
import time
import urllib.request
from datetime import datetime

BASE = os.environ.get("BASE_URL", "http://localhost:8080")
EMAIL = os.environ.get("ADMIN_EMAIL", "admin@smartroute.local")
PASSWORD = os.environ["ADMIN_PASSWORD"]
ORDERS = int(os.environ.get("ORDERS", "50"))


def call(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request) as response:
            text = response.read().decode()
    except urllib.error.HTTPError as error:
        raise SystemExit(f"{method} {path} failed: {error.code} {error.read().decode()}") from error
    return json.loads(text) if text else None


def login():
    return call("POST", "/api/auth/login", body={"email": EMAIL, "password": PASSWORD})["accessToken"]


def instant(value):
    """Parse an ISO-8601 instant as the API prints it (nanoseconds are truncated to microseconds)."""
    if value.endswith("Z"):
        value = value[:-1]
    if "." in value:
        head, fraction = value.split(".")
        value = head + "." + fraction[:6]
    return datetime.fromisoformat(value)


def create_order(token, warehouse_id):
    return call("POST", "/api/orders", token, {
        "warehouseId": warehouse_id,
        "customerName": "Event latency probe",
        "dropAddress": "1 Test Street",
        "dropLatitude": 12.99,
        "dropLongitude": 77.62,
        "priority": "NORMAL",
        "weightKg": 5,
        "volumeM3": 0.3,
    })["id"]


def await_event(token, order_id, timeout=30.0):
    """Poll until the order's event has been recorded; returns the event and the client's wait in ms."""
    started = time.perf_counter()
    deadline = started + timeout
    while time.perf_counter() < deadline:
        body = call("GET", f"/api/events?aggregateType=order&aggregateId={order_id}", token)
        if body["totalElements"]:
            return body["content"][0], (time.perf_counter() - started) * 1000
        time.sleep(0.02)
    raise SystemExit(f"Order {order_id}'s event never arrived within {timeout} s")


def percentile(values, p):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p / 100 * len(ordered))) - 1)]


def report(label, values):
    print(f"{label:<34} n={len(values):<4} p50 {percentile(values, 50):7.1f} ms   "
          f"p95 {percentile(values, 95):7.1f} ms   max {max(values):7.1f} ms   "
          f"mean {statistics.fmean(values):7.1f} ms")


def main():
    token = login()
    # /api/warehouses answers with a plain array, not a page.
    warehouse_id = call("GET", "/api/warehouses", token)[0]["id"]
    outbox_before = call("GET", "/api/events/outbox", token)

    pipeline, client_wait = [], []
    for _ in range(ORDERS):
        order_id = create_order(token, warehouse_id)
        event, waited = await_event(token, order_id)
        pipeline.append((instant(event["recordedAt"]) - instant(event["occurredAt"])).total_seconds() * 1000)
        client_wait.append(waited)

    print(f"{ORDERS} orders created one at a time against {BASE}")
    print(f"outbox before: {outbox_before}")
    print(f"outbox after:  {call('GET', '/api/events/outbox', token)}")
    print()
    report("commit -> recorded (the event)", pipeline)
    report("create -> visible (the client)", client_wait)


if __name__ == "__main__":
    main()
