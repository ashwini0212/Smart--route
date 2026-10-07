#!/usr/bin/env bash
# Checks a deployed SmartRoute from the outside: the page, the backend's readiness through the proxy, and
# that a protected API refuses an anonymous caller. Exits non-zero on the first failure.
#   bash deploy/smoke-test.sh https://203-0-113-7.sslip.io
set -euo pipefail
BASE="${1:?usage: smoke-test.sh <public url>}"
BASE="${BASE%/}"

# The backend can take a minute on first start (Flyway, seed data, Kafka topics); Caddy may still be
# fetching its certificate. Wait up to 5 minutes for health before judging anything else.
for attempt in $(seq 1 60); do
  if curl -fsS --max-time 5 "$BASE/actuator/health" 2>/dev/null | grep -q '"UP"'; then
    break
  fi
  if [ "$attempt" -eq 60 ]; then
    echo "FAIL $BASE/actuator/health did not report UP within 5 minutes" >&2
    exit 1
  fi
  sleep 5
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
