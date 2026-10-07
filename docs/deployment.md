# Deploying SmartRoute on free tiers

SmartRoute can run publicly without a paid server, by splitting it across five free plans:

| Part | Service (free plan) | Why this one |
|---|---|---|
| Frontend | **Vercel** | Builds the Vite app from `frontend/` and forwards `/api` to the backend, so the browser sees one site |
| Backend | **Render** web service | Runs the existing `backend/Dockerfile` unchanged |
| Redis | **Render Key Value** | Same region and private network as the backend, created by the same Blueprint |
| PostgreSQL | **Neon** | Free with no expiry date. Render's free PostgreSQL is deleted 30 days after it is created |
| Kafka | **Aiven for Apache Kafka** | The only permanently free managed Kafka found. Redpanda Serverless is a 30-day trial, and Render has no Kafka |

None of the steps below needs a paid plan or adds one. Whenever a page asks you to choose a plan, choose
**Free**. If a page will not let you continue without a paid plan, stop there: something has changed on
that service's side.

What was tested, and what was not. Nothing has been deployed to these services yet. Everything below was
checked locally against stand-ins for their limits:
- the backend container under a 512 MB memory limit;
- a Kafka broker that accepts only TLS with a username and password and has its own certificate authority,
  as Aiven's does;
- a Redis with a password.

The first real deployment is the test of the rest.

## What changed in the code to make this fit

1. **One Kafka topic instead of fourteen.** Aiven's free plan allows five topics. The default layout uses
   seven event topics, each with its own dead-letter topic. With `EVENTS_SINGLE_TOPIC` set (the `cloud`
   profile sets it to `smartroute.events`), every event goes to that one topic and its `.DLT`. Both
   consumers already read every topic and act on the envelope's `type`, so nothing they do changes.
   Records are still keyed by order, so one order's events keep their order. The cost: a future consumer
   that wants only one kind of event must skip the others. The default layout is unchanged for
   docker-compose and CI. See `EventTopics`, `EventTopicsTest` and `SingleTopicEventFlowTest`.
2. **A `cloud` Spring profile** (`application-cloud.yml`) for managed services:
   - Redis from one URL;
   - Kafka over TLS with SASL/SCRAM, with the provider's CA certificate passed as text;
   - smaller connection and thread pools for 512 MB.
3. **JVM flags for 512 MB** in `render.yaml`.

### Memory and start-up time, measured locally

The backend ran in a container limited to 512 MB, with the `seed,cloud` profiles, the Kafka and Redis
stand-ins above, and the flags in `render.yaml`. The load was 40 s of `scripts/perf/load.py 16 40 mixed`
(16 concurrent clients).

| | Result |
|---|---|
| Resident memory, idle after start | 372 MiB |
| Peak memory during the load | 455 MiB, no out-of-memory kill, no restart, every response 200 |
| Without the tuned flags | 496 MiB resident, the limit reached. That is why the flags exist |
| Start-up with half a CPU | ready after 61 s |
| Start-up with a tenth of a CPU | ready after 315 s |

Render describes the free instance as "512 MB, less than 1 CPU" without an exact figure, so a cold start
probably takes between one and five minutes. Throughput was measured on a 4-core machine and says nothing
about Render, so it is not quoted here.

## Before you start

- Merge the deployment pull request into `main`. Render and Vercel deploy from `main`.
- Use one region for everything. The steps below use **Singapore**, the closest region Render offers to
  the seed data's Bengaluru coordinates. With services in different regions, every database and Kafka call
  crosses continents.
- Keep a text file open to collect the values marked **→ save** below. You paste them into Render in step 3.

## Step 1: PostgreSQL on Neon

1. Sign up at neon.com (the Free plan needs no card).
2. Create a project:
   - Postgres version 17;
   - region **AWS Asia Pacific (Singapore)**;
   - database name `smartroute`.
3. Open **Connect**. Turn **Connection pooling off** to get the direct connection, then copy the
   connection string. It looks like:
   `postgresql://smartroute_owner:AbC123@ep-cool-name-123456.ap-southeast-1.aws.neon.tech/smartroute?sslmode=require&channel_binding=require`
4. Split it into three values, rewriting the URL into the Java form:
   - **→ save** `DB_URL` = `jdbc:postgresql://ep-cool-name-123456.ap-southeast-1.aws.neon.tech/smartroute?sslmode=require`
     (prefix `jdbc:`, drop the user and password, and drop `&channel_binding=require`);
   - **→ save** `DB_USERNAME` = `smartroute_owner`;
   - **→ save** `DB_PASSWORD` = `AbC123`.

Why the direct connection: the pooled one goes through PgBouncer in transaction mode, which is a poor fit
for Flyway's migrations and the JDBC driver's prepared statements. The backend has its own pool of four
connections.

## Step 2: Kafka on Aiven

1. Sign up at aiven.io (the free Kafka plan needs no card).
2. Click **Create service**, then:
   - choose **Apache Kafka**;
   - service tier **Free**;
   - the region group closest to Singapore;
   - name it `smartroute-kafka`.

   Wait until it shows **Running**.
3. Turn on password authentication: open the service's **Advanced configuration**, add
   `kafka_authentication_methods.sasl`, set it to on, and save. (Certificate authentication is the default.
   SASL is used here because a username and password fit in environment variables.)
4. On the service **Overview**, under connection information, choose the **SASL** method. From there:
   - **→ save** `KAFKA_BOOTSTRAP_SERVERS` = the **Service URI** as `host:port`. The SASL port is different
     from the default certificate port.
   - **→ save** `KAFKA_USERNAME` = `avnadmin`.
   - **→ save** `KAFKA_PASSWORD` = the password shown.
   - Download the **CA certificate** (`ca.pem`). **→ save** `KAFKA_CA_CERT` = the whole file, from
     `-----BEGIN CERTIFICATE-----` to `-----END CERTIFICATE-----` inclusive.
5. Topics: the backend creates `smartroute.events` (2 partitions) and `smartroute.events.DLT` on its first
   start, using Aiven's default replication factor. If its log says topic creation was refused, do two
   things:
   - create those two topics yourself in the service's **Topics** tab;
   - add `EVENTS_CREATE_TOPICS=false` to the backend's environment on Render.

## Step 3: Backend and Redis on Render

1. Sign up at render.com with your GitHub account and allow it to read `ashwini0212/Smart--route`.
2. Click **New → Blueprint**, pick the repository and branch `main`. Render reads `render.yaml` and lists
   two free resources: the web service `smartroute-api` and the Key Value instance `smartroute-cache`.
3. Render asks for the values marked `sync: false`. Paste:

   | Variable | Value |
   |---|---|
   | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | from step 1 |
   | `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_USERNAME`, `KAFKA_PASSWORD`, `KAFKA_CA_CERT` | from step 2 (the certificate can be pasted as several lines) |
   | `DEMO_USER_PASSWORD` | a password of at least 12 characters, made up by you. It is the password of every demo login, see the warning below |
   | `CORS_ALLOWED_ORIGINS` | `https://smartroute.vercel.app` for now. You replace it with your real Vercel address in step 4 |

   Render generates `JWT_SECRET` itself and connects `REDIS_URL` to the Key Value instance. Everything
   else has a value in `render.yaml`.
4. Click **Apply**. The first build compiles the backend inside Docker and takes several minutes. Then the
   service starts, runs the database migrations and loads the fictional seed data.
5. Open `https://smartroute-api.onrender.com/actuator/health` (use your service's own address from the
   Render dashboard). It should say `{"status":"UP"}`.
6. **If your service address is not `smartroute-api.onrender.com`** (Render adds a suffix when that name is
   taken): change the two `destination` lines in `frontend/vercel.json` to your address, then commit and
   push to `main`.

## Step 4: Frontend on Vercel

1. Sign up at vercel.com with your GitHub account (Hobby plan).
2. Click **Add New → Project** and import `ashwini0212/Smart--route`.
3. Set **Root Directory** to `frontend`. Vercel detects Vite. The build command and output folder come from
   `frontend/vercel.json`. **No environment variables are needed.**
4. Click **Deploy** and note the address, e.g. `https://smart-route-abc.vercel.app`.
5. Back in Render, set `CORS_ALLOWED_ORIGINS` on `smartroute-api` to exactly that address and save. Render
   redeploys.

## Step 5: Check it

```bash
bash deploy/smoke-test.sh https://smart-route-abc.vercel.app
```

It waits up to 8 minutes for a sleeping backend to wake, then checks three things and prints `ok` or
`FAIL` for each:
- `/actuator/health` reports UP through Vercel;
- the page is the frontend;
- an anonymous call to `/api/orders` is refused with 401.

Then open the address in a browser and log in as `dispatcher@smartroute.local` with your
`DEMO_USER_PASSWORD`. To see the live map move, assign some orders first, from the Orders page or with
auto-dispatch. The simulator moves only drivers who have a delivery.

## Every environment variable

**Vercel:** none.

**Render, `smartroute-api`:**

| Variable | Set by | Value |
|---|---|---|
| `DB_URL` | you | `jdbc:postgresql://<neon host>/<db>?sslmode=require` |
| `DB_USERNAME` | you | Neon role |
| `DB_PASSWORD` | you | Neon password |
| `KAFKA_BOOTSTRAP_SERVERS` | you | Aiven SASL `host:port` |
| `KAFKA_USERNAME` | you | `avnadmin` |
| `KAFKA_PASSWORD` | you | Aiven password |
| `KAFKA_CA_CERT` | you | contents of Aiven's `ca.pem` |
| `DEMO_USER_PASSWORD` | you | at least 12 characters |
| `CORS_ALLOWED_ORIGINS` | you | your Vercel address, `https://…vercel.app` |
| `JWT_SECRET` | Render (generated) | do not change; changing it logs everyone out |
| `REDIS_URL` | Render (from `smartroute-cache`) | internal `redis://…:6379` |
| `SERVER_PORT` | `render.yaml` | `10000` |
| `SPRING_PROFILES_ACTIVE` | `render.yaml` | `seed,cloud` |
| `JAVA_TOOL_OPTIONS` | `render.yaml` | the 512 MB flags |
| `DEPLOY_ENV` | `render.yaml` | `production` (a tag on every metric) |
| `EVENTS_RELAY_INTERVAL` | `render.yaml` | `2s` |
| `SIMULATE_DRIVERS` / `SIMULATE_TRAFFIC` | `render.yaml` | `true` / `false` |
| `ASSISTANT_ENABLED` | `render.yaml` | `false` |

Optional, only when needed:
- `EVENTS_CREATE_TOPICS=false` if Aiven refuses topic creation (step 2.5);
- `KAFKA_SASL_MECHANISM=SCRAM-SHA-512` or `PLAIN` if you pick that mechanism in Aiven (the default is
  `SCRAM-SHA-256`);
- `ANTHROPIC_API_KEY` with `ASSISTANT_ENABLED=true` to turn on the assistant. This is the one paid item:
  each question is billed to that key.

## What the free plans cost you in behaviour

- **The backend sleeps.** Render stops a free service after 15 minutes without a request. The next visitor
  waits for a cold start, measured above at one to five minutes depending on the CPU share. If that takes
  longer than Vercel's 120-second limit for a first response, the page shows an error, and a reload once
  the backend is up works. While it sleeps, the driver simulator and the scheduled jobs are stopped too.
- **Kafka powers off after 24 hours without traffic.** Aiven turns off a free Kafka service when nothing
  has been produced or consumed for a day, and it stays off until you press **Power on** in the console.
  While it is off:
  - orders, routing and assignment keep working, because Kafka is not in the request path;
  - events wait in the outbox table;
  - the live map and the event log stop updating.

  Once Kafka is back, the relay publishes the backlog in order. That is the transactional outbox doing its
  job (Phase 9).
- **Redis is memory only.** Render's free Key Value (25 MB) loses its contents on restart. Everything in it
  is rebuildable: cached routes are recomputed, and live positions fall back to the database.
- **Neon suspends its compute** after 5 idle minutes. The first query afterwards takes a moment while it
  resumes. The free plan has 100 compute-hours a month. A backend awake around the clock would use them up,
  but a demo that sleeps between visits will not.
- **Login rate limit per address.** Requests reach the backend through Vercel's servers, so the per-address
  limit (20 attempts a minute) counts Vercel's address rather than each visitor's. The per-account limit
  (5 attempts a minute) is unaffected. On a demo this matters little; a real deployment would put the API
  on its own domain or trust a header only Vercel can set.
- **No backups, one replica.** Fine for a portfolio demo, and the opposite of what a delivery company would
  run.

## The demo logins on a public site

The seed data creates five logins (admin, dispatcher, viewer, two drivers) that share `DEMO_USER_PASSWORD`.
On a public site that password is an administrator password: anyone who has it can create, assign and
cancel orders, and change routing weights. Give it only to people you trust with that.

## Running it locally is unchanged

`docker compose up --build` still runs the full stack with the default seven-topic layout. The `cloud`
profile and `render.yaml` are only used on Render.
