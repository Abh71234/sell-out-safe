# Sell-out safe — Reservation API (Java 21)

Backend track solution: make the Reservation API **idempotent** and **outage-safe**.
Everything runs in Docker — you only need Docker Desktop. No local Java/Maven/Go needed.

## Layout

```
sell-out-safe/
├── app/                      # the Java 21 service (your solution)
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/launchday/App.java
├── authority/                # provided mock Central Authority (Go)
├── checks/                   # provided organiser test suite (Go)
├── seed/init.sql             # Postgres schema
├── docker-compose.yml        # postgres + authority + reservation-api + checks
└── Makefile
```

## Run

```bash
make up        # build & start postgres + authority + reservation-api
make check     # run the provided checks (TRACK=backend) — expect all PASS
make logs      # tail the API logs
make down      # stop & wipe
```

Ports on the host: API `http://127.0.0.1:8080`, authority `http://127.0.0.1:9001`
(authority is mapped to 9001 because a local VPN occupies 9000; containers still
talk to it internally on 9000).

## Toggle the authority manually (for demos / the sealed constraint)

```bash
make authority MODE=down          # or slow | healthy | conflicting
make reset                        # reset authority + API state
curl -s localhost:8080/health     # {"authority":...,"mode":"live|standin"}
```

## How it works (defense notes)

- **Idempotency** — a partial unique index on `(user_id, idempotency_key)` in
  Postgres. The first request *claims* the row (`INSERT ... 'processing'`); retries
  hit `23505` and read back the same `reservationId`. A reused key with a different
  `body_hash` returns `422 idempotency_key_reused`. Concurrent replays serialize on
  the unique index, so exactly one reservation exists per key (**I1, I5**).
- **No oversell (live)** — the authority owns the real count and decrements
  atomically; we forward and faithfully map its `201/409`. Under 200 concurrent
  requests for stock 50 → exactly 50 confirmed (**I2**).
- **Breaker** — a background thread polls the authority `/health` every second.
  Slow == down (2s timeout). It flips `mode` between `live` and `standin`, and
  `/health` reports it truthfully within a couple of seconds.
- **Stand-in** — while down, authorize against a per-item **shadow count** capped by
  `STANDIN_MAX_PER_ITEM` (default 10); persist `pending` rows **in Postgres** (so a
  crash loses nothing), return `202`. Exhaustion → `409 standin_limit_reached` /
  `insufficient_stock`.
- **Replay** — on recovery, pending rows are re-sent **in order** reusing the same
  `reservationId` (authority is idempotent on it) → `confirmed` or `reversed`.
  Idempotent, so a restart mid-replay is safe (**I3, I4**). Triggered automatically
  by the breaker and via `POST /admin/reconcile`.
- **Stretch (conflict)** — under `conflicting`, replay refusals become `reversed`,
  call `POST /releases`, and write an audit row to `reservation_events`.
  `GET /admin/audit?reservationId=` exposes the trail.

## Where the sealed constraint goes

The stand-in limit lives entirely in `standinAuthorize(...)` in `App.java`. When the
0:15 constraint changes how stand-in authorisation is limited, edit that one method.
