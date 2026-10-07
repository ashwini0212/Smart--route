# Deploying SmartRoute to a public URL

SmartRoute runs as one Docker Compose stack on one Linux server, with Caddy in front for HTTPS. This page
says why, what it needs, and how to do it. Nothing here claims a site is running: the only proof of that
is loading its URL, which `deploy/smoke-test.sh` does.

## Why one server running Compose

The stack is PostgreSQL, Redis, Kafka, a Spring Boot backend and an nginx static site. The free tiers of
the "push your repo" platforms (Render, Koyeb, Railway's trial) give a web service around 512 MB and no
Kafka at all, so using them would mean an external managed Kafka, an external Redis and a database with an
expiry date, three accounts and three sets of credentials, for a stack that idles at under 1 GB. A single
virtual machine runs exactly what `docker compose up` runs locally, so the deployed system is the tested
system, and one `.env` file holds every secret.

The cost of that choice, stated plainly: one machine is one point of failure; there is no replica, no
managed backup of PostgreSQL, and a single Kafka broker (replication factor 1). That is right for a
portfolio demo and wrong for a real delivery company, which would use a managed database, a three-broker
Kafka cluster and more than one backend instance behind a load balancer.

### Memory, measured

The production overlay running locally, idle a few minutes after start with the seed data loaded and the
driver simulator on (`docker stats`, 2026-10-07): backend 463 MiB (capped at 1 GiB), Kafka 351 MiB (heap
capped at 512 MiB), PostgreSQL 61 MiB, Caddy 13 MiB, Redis 6 MiB, nginx 5 MiB: about 900 MiB in total.
That is an idle figure, not a load test. A 4 GB server leaves room for traffic and for building the
images; `setup-server.sh` also adds 2 GB of swap, because the Maven build is the hungriest moment.

### Which server

Any Ubuntu 22.04/24.04 machine with a public IP and 4 GB of RAM works, x86-64 or ARM64 (every image used
is published for both). Two that fit:

| Option | Cost | Note |
|---|---|---|
| Oracle Cloud "Always Free" Ampere A1 (ARM) | free | A card is needed to verify the account. The free ARM shape is often out of capacity in popular regions; retry, or pick a less busy home region at sign-up (it cannot be changed later). |
| Hetzner Cloud, smallest 4 GB shared-vCPU type | a few euros a month (check the current price) | Reliable to create, billed hourly. |

## What is in the repository

| File | Purpose |
|---|---|
| `deploy/docker-compose.prod.yml` | Overlay on the normal `docker-compose.yml`: adds Caddy on ports 80/443, removes every other published port, restart policies, memory caps, CORS origin = the public URL |
| `deploy/Caddyfile` | HTTPS with an automatic Let's Encrypt certificate; `/api` and `/actuator/health` to the backend, everything else to nginx |
| `deploy/env.production.example` | Every setting the server needs, with `CHANGE-ME` where a secret goes |
| `deploy/setup-server.sh` | One-time: Docker, swap, firewall (including Oracle's default iptables rules), clone, `.env` from the example |
| `deploy/deploy.sh` | Build and (re)start the stack, then run the smoke test against the public URL |
| `deploy/smoke-test.sh` | From outside: health is UP, the page is the frontend, an anonymous API call is refused with 401 |
| `.github/workflows/deploy.yml` | After CI passes on `main` (or on demand), SSH to the server and run `deploy.sh` for the exact commit CI tested. Skips itself until `DEPLOY_HOST` is set. |

### Why Caddy sends /api straight to the backend

nginx is the edge in the local stack and overwrites `X-Forwarded-For` with the address it sees, which the
backend trusts only from a private-network peer (the login rate limit depends on it, Phase 5). With
Caddy → nginx → backend, nginx would see Caddy's address and every visitor would share one rate-limit
bucket. Caddy → backend keeps a single hop: Caddy discards whatever `X-Forwarded-For` the client sent and
writes the real address. Verified locally: fifteen failed logins, each sending a different forged
`X-Forwarded-For`, were refused with 429 from the sixth on, so the forged header was ignored.
The second reason is the live map's server-sent event stream: Caddy flushes `text/event-stream` responses
at once, while nginx buffers proxied responses unless told not to. Verified locally: the `hello` frame and
the 20 s heartbeat arrive through Caddy as they are sent.

## Steps

1. **Create the server** (Oracle or Hetzner above). Allow inbound TCP 22, 80 and 443 in the provider's
   firewall (Oracle: the subnet's security list; Hetzner: no firewall by default). Note its public IP.
2. **Pick the address.** With a domain, point an A record at the IP. Without one, use the free
   `sslip.io` name for the IP: `203.0.113.7` becomes `203-0-113-7.sslip.io`, which needs no setup.
3. **Prepare the server** over SSH:
   ```bash
   git clone https://github.com/ashwini0212/Smart--route.git ~/smartroute
   bash ~/smartroute/deploy/setup-server.sh
   ```
   Log out and back in so your user can use Docker.
4. **Fill in `~/smartroute/.env`.** Set `SITE_ADDRESS` and `PUBLIC_URL` to the address from step 2, and
   generate each secret with `openssl rand -base64 48` (`POSTGRES_PASSWORD`, `JWT_SECRET`, and a
   `DEMO_USER_PASSWORD` of at least 12 characters).
5. **Start it:** `cd ~/smartroute && bash deploy/deploy.sh`. The first build takes several minutes. It ends
   with three `ok` lines from the smoke test, or a `FAIL` line saying what did not answer.
6. **Optional, automatic redeploys.** Make a key pair for GitHub (`ssh-keygen -t ed25519 -f deploy_key -N ""`),
   append `deploy_key.pub` to `~/.ssh/authorized_keys` on the server, and add three repository secrets:
   `DEPLOY_HOST` (the IP), `DEPLOY_USER` (your SSH user), `DEPLOY_SSH_KEY` (the contents of `deploy_key`).
   Every green CI run on `main` then redeploys that commit. The workflow trusts the server's SSH host key
   the first time it sees it (`ssh-keyscan`); pin it in `known_hosts` if that matters to you.

## The demo logins on a public site

The `seed` profile creates five logins (admin, dispatcher, viewer, two drivers) that all share
`DEMO_USER_PASSWORD`. On a public site that password is an administrator password: anyone who has it can
create, assign and cancel orders, and change routing weights. Give it only to people you trust with that.
Separate per-role passwords (so a read-only viewer login could be published) would be a small change to
`DemoDataSeeder`, not made here.

## What the live site shows, and what is simulated

- The data is the fictional seed set: 120 drivers and 600 orders around a **synthetic** road network (a
  generated city, not a real map), unless a real OSM extract is configured with `ROAD_NETWORK_DIR`.
- `env.production.example` turns the **[SIMULATION]** driver simulator on, so the live map has movement.
  It only moves drivers who have a delivery, so dispatch some orders first (Orders page, or auto-dispatch).
  Every simulated position is labelled `SIMULATION` in the API and the UI; set `SIMULATE_DRIVERS=false` for
  a site that shows only real input.
- The assistant stays off unless you set both `ASSISTANT_ENABLED=true` and `ANTHROPIC_API_KEY`, and every
  question then costs money on that key.
- Map tiles come from OpenStreetMap's public tile server, whose usage policy allows light use like a demo.

## Operating it

```bash
cd ~/smartroute
alias sr='docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml --env-file .env'
sr ps                    # state and health of each container
sr logs -f backend       # follow the backend log
sr restart backend       # restart one service
bash deploy/deploy.sh    # rebuild and restart after a git pull
sr down                  # stop everything (data volumes are kept; add -v to delete them)
```

Backups are not automated. A manual one: `sr exec postgres pg_dump -U smartroute smartroute > backup.sql`.
