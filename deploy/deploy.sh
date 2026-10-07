#!/usr/bin/env bash
# Build and (re)start the production stack on the server, then check it answers through the public address.
# Used by hand and by .github/workflows/deploy.yml. Run from the repository root.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo ".env is missing: cp deploy/env.production.example .env and fill it in" >&2
  exit 1
fi
if grep -qE '^[^#]*CHANGE-ME' .env; then
  echo ".env still contains CHANGE-ME placeholders" >&2
  exit 1
fi

compose() { docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml --env-file .env "$@"; }

compose up -d --build --remove-orphans
# Images replaced by this build are no longer used by anything.
docker image prune -f >/dev/null

PUBLIC_URL="$(grep -E '^PUBLIC_URL=' .env | cut -d= -f2-)"
bash deploy/smoke-test.sh "$PUBLIC_URL"
