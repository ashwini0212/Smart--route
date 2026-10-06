#!/usr/bin/env python3
"""Measure how long a driver position takes to reach a dashboard, and how many frames one arrives per second.

Opens the server-sent event stream and, for every driver-moved frame, compares the position's own timestamp
(set when the position was recorded, before it was written to the outbox) with the moment this client read the
frame. That covers the whole path: transaction, outbox relay, Kafka, consumer, Redis, SSE write, network.

It needs something to watch, so run it with the driver simulator on (SIMULATE_DRIVERS=true). The latencies are
real; the movement being watched is not.

Usage: BASE_URL=http://localhost:8080 ADMIN_EMAIL=... ADMIN_PASSWORD=... python3 scripts/perf/live_latency.py
"""
import json
import os
import statistics
import time
import urllib.request
from collections import Counter
from datetime import datetime, timezone

BASE = os.environ.get("BASE_URL") or "http://localhost:8080"
EMAIL = os.environ.get("ADMIN_EMAIL") or "admin@smartroute.local"
PASSWORD = os.environ["ADMIN_PASSWORD"]
SECONDS = float(os.environ.get("SECONDS") or "60")


def login():
    request = urllib.request.Request(BASE + "/api/auth/login",
                                     data=json.dumps({"email": EMAIL, "password": PASSWORD}).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request) as response:
        return json.loads(response.read().decode())["accessToken"]


def instant(value):
    if value.endswith("Z"):
        value = value[:-1]
    if "." in value:
        head, fraction = value.split(".")
        value = head + "." + fraction[:6]
    return datetime.fromisoformat(value).replace(tzinfo=timezone.utc)


def percentile(values, p):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p / 100 * len(ordered))) - 1)]


def main():
    token = login()
    request = urllib.request.Request(BASE + "/api/tracking/stream",
                                    headers={"Authorization": "Bearer " + token, "Accept": "text/event-stream"})
    latencies = []
    frames = Counter()
    sources = Counter()
    started = time.time()
    event = None
    with urllib.request.urlopen(request) as stream:
        while time.time() - started < SECONDS:
            line = stream.readline().decode().strip()
            if line.startswith("event:"):
                event = line[len("event:"):]
            elif line.startswith("data:"):
                frames[event] += 1
                if event == "driver-moved":
                    payload = json.loads(line[len("data:"):])
                    arrived = datetime.now(timezone.utc)
                    latencies.append((arrived - instant(payload["at"])).total_seconds() * 1000)
                    sources[payload.get("source", "unknown")] += 1
    elapsed = time.time() - started

    print(f"Watched {BASE}/api/tracking/stream for {elapsed:.1f} s")
    print("frames by type:", dict(frames))
    print("driver-moved by source:", dict(sources))
    if latencies:
        print(f"driver-moved frames: {len(latencies)} ({len(latencies) / elapsed:.1f}/s)")
        print(f"position recorded -> frame read: p50 {percentile(latencies, 50):.0f} ms  "
              f"p95 {percentile(latencies, 95):.0f} ms  max {max(latencies):.0f} ms  "
              f"mean {statistics.fmean(latencies):.0f} ms")
    else:
        print("No driver-moved frames arrived. Is the driver simulator on, and are any deliveries assigned?")


if __name__ == "__main__":
    main()
