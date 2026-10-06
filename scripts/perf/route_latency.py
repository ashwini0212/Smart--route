"""Sequential latency of the route API as one client sees it (HTTP keep-alive, localhost).

Usage: python3 scripts/perf/route_latency.py <DEMO_USER_PASSWORD>   (stack running with the seed profile)
"""
import http.client, json, random, statistics, sys, time

HOST, PORT = "localhost", 8080
conn = http.client.HTTPConnection(HOST, PORT)

def call(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    start = time.perf_counter()
    conn.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
    resp = conn.getresponse()
    data = resp.read()
    return resp.status, data, (time.perf_counter() - start) * 1000

status, data, _ = call("POST", "/api/auth/login", {"email": "dispatcher@smartroute.local", "password": sys.argv[1]})
assert status == 200, data
token = json.loads(data)["accessToken"]

rnd = random.Random(7)
def point():
    return {"latitude": rnd.uniform(12.965, 13.075), "longitude": rnd.uniform(77.585, 77.700)}
pairs = [(point(), point()) for _ in range(300)]

def run(mode, pairs, label):
    times, settled, cached = [], [], 0
    for a, b in pairs:
        status, data, ms = call("POST", f"/api/routes/{mode}", {"from": a, "to": b}, token)
        assert status == 200, data
        r = json.loads(data)
        times.append(ms); settled.append(r["nodesSettled"]); cached += r["cached"]
    times.sort()
    p = lambda q: times[min(len(times) - 1, int(q * len(times)))]
    print(f"{label:34s} n={len(times)} cached={cached:3d}  p50={p(0.50):6.2f} ms  p95={p(0.95):6.2f} ms  p99={p(0.99):6.2f} ms  max={times[-1]:6.2f} ms  mean settled={statistics.mean(settled):.0f}")

for _ in range(50):  # JIT warm-up on other pairs
    a, b = point(), point()
    call("POST", "/api/routes/fastest", {"from": a, "to": b}, token)

run("fastest", pairs, "fastest, first request (computed)")
run("fastest", pairs, "fastest, repeated (cache hit)")
run("shortest", pairs, "shortest, first request (computed)")
run("shortest", pairs, "shortest, repeated (cache hit)")

def baseline(path, label, n=300):
    times = []
    for _ in range(n):
        status, data, ms = call("GET", path, None, token)
        assert status == 200, data
        times.append(ms)
    times.sort()
    print(f"{label:34s} n={n}  p50={times[n//2]:6.2f} ms  p95={times[int(n*0.95)]:6.2f} ms")

def alt(pairs, label):
    times = []
    for a, b in pairs:
        status, data, ms = call("POST", "/api/routes/alternatives", {"from": a, "to": b, "mode": "FASTEST", "count": 1}, token)
        assert status == 200, data
        times.append(ms)
    times.sort()
    n = len(times)
    print(f"{label:34s} n={n}  p50={times[n//2]:6.2f} ms  p95={times[int(n*0.95)]:6.2f} ms")

baseline("/api/warehouses", "GET /api/warehouses (baseline)")
alt(pairs, "alternatives k=1, first (computed)")
alt(pairs, "alternatives k=1, repeated (cached)")
