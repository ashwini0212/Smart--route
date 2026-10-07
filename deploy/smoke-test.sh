#!/usr/bin/env bash
# Checks a deployed SmartRoute from the outside: the page, the backend's readiness through the proxy, and
# that a protected API refuses an anonymous caller. Exits non-zero on the first failure.
#   bash deploy/smoke-test.sh https://smartroute.vercel.app      (through Vercel's rewrite)
#   bash deploy/smoke-test.sh https://smartroute-api.onrender.com (the backend alone: the page check fails)
set -euo pipefail
BASE="${1:?usage: smoke-test.sh <public url>}"
BASE="${BASE%/}"

# A free Render service that has spun down takes one to several minutes to start again (measured: about 1 min
# with half a CPU, about 5 min with a tenth). Wait up to 8 minutes for health before judging anything else.
for attempt in $(seq 1 16); do
  if curl -fsS --max-time 30 "$BASE/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    break
  fi
  if [ "$attempt" -eq 16 ]; then
    echo "FAIL $BASE/actuator/health did not report UP within 8 minutes" >&2
    exit 1
  fi
  sleep 2
done
echo "ok   $BASE/actuator/health is UP"

if curl -fsS --max-time 10 "$BASE/" | grep -qi '<div id="root">'; then
  echo "ok   $BASE/ serves the frontend"
else
  echo "FAIL $BASE/ did not return the frontend page" >&2
  exit 1
fi

status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$BASE/api/orders")"
if [ "$status" = "401" ]; then
  echo "ok   $BASE/api/orders refuses an anonymous request (401)"
else
  echo "FAIL $BASE/api/orders answered $status to an anonymous request, expected 401" >&2
  exit 1
fi
