# Seat Reservation at Scale

A small JSON HTTP service that sells **assigned seats** for a show and stays correct under a stampede:
it never sells the same seat twice, never lets a user exceed their booking limit, and never
double-charges a retried request — then exposes health, metrics and structured logs so you can watch
it behave correctly in real time.

- **Live URL:** `https://<your-app>.onrender.com`  *(fill in after deploy)*
- **Stack:** Java 21 · Spring Boot 3.5 · MySQL 8 (InnoDB) · Flyway · Micrometer/Prometheus · Docker
- **Design deep-dive:** see [WRITEUP.md](WRITEUP.md)

---

## Quick start (clean checkout → running in one command)

Requires Docker.

```bash
docker compose up --build
```

This starts MySQL + the app. The app waits for the DB, runs Flyway migrations, and serves on
`http://localhost:8080`. Then:

```bash
# health
curl localhost:8080/readyz

# create a show (admin)
curl -s -X POST localhost:8080/shows \
  -H "X-Admin-Token: admin-local" -H "Content-Type: application/json" \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'

# mint a user token (dev helper)
TOKEN=$(curl -s -X POST localhost:8080/auth/token \
  -H "Content-Type: application/json" -d '{"user_id":"alice"}' \
  | sed -E 's/.*"token":"([^"]*)".*/\1/')

# reserve a seat (identity comes from the token, not the body)
curl -s -X POST localhost:8080/shows/<SHOW_ID>/reserve \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: $(uuidgen)" -d '{"seats":["A1"]}'

# show state + reconciliation
curl -s localhost:8080/shows/<SHOW_ID>
```

Build / test without Docker compose:

```bash
./mvnw clean package            # builds the jar (unit/integration tests skipped if Docker absent)
./mvnw test                     # runs the Testcontainers concurrency proof (needs Docker)
```

---

## API

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/auth/token` | — | Dev helper: `{ "user_id": "alice" }` → signed JWT. |
| `POST` | `/shows` | `X-Admin-Token` | `{ name, seats[], price_paise, per_user_limit? }` → show with all seats `available`. |
| `GET`  | `/shows/{id}` | — | Per-seat status + counts; `available + held + confirmed == total_seats`. |
| `POST` | `/shows/{id}/reserve` | `Bearer` | `{ seats[], idempotency_key? }` (+ optional `Idempotency-Key` header). |
| `POST` | `/reservations/{id}/confirm` | `Bearer` (owner) | `held → confirmed`. |
| `POST` | `/reservations/{id}/cancel`  | `Bearer` (owner) | release seats → `available`. |
| `GET`  | `/healthz` · `/health/liveness` | — | liveness. |
| `GET`  | `/readyz` · `/health/readiness` | — | readiness — checks DB, **fails closed (503)**. |
| `GET`  | `/metrics` | — | Prometheus exposition. |

### Reserve outcomes
- `201 Created` — seats secured (status `held`, carries `expires_at`).
- `200 OK` — **idempotent replay**: same key + same seats returns the original reservation.
- `409 Conflict` — clean decline, with a `code`:
  - `seat_taken` — someone else got it;
  - `per_user_limit` — user already holds the max;
  - `idempotency_conflict` — same key reused with **different** seats;
  - `transient_conflict` — lock contention after retries (safe to retry; nothing was sold).
- `404` unknown show/seat · `401` missing/invalid token · `400` malformed request.

### Documented behaviours (the spec asks us to choose and state these)
- **Partial multi-seat requests are ALL-OR-NOTHING.** `["A12","A13"]` where only one is free →
  `409 seat_taken`, nothing held. Enforced atomically inside one transaction.
- **Holds auto-expire.** A reserve creates a time-boxed hold (`HOLD_TTL_SECONDS`, default 120s).
  A background sweeper returns expired holds to `available` and never touches a `confirmed` seat.
  Owners may also `confirm` (finalize) or `cancel` (release) explicitly.
- **Identity is token-derived.** The `user_id` is read only from the JWT. A spoofed body field is
  ignored; confirm/cancel only act on the caller's own reservations.
- **Money is integer paise** (`BIGINT`), never floating point. `amount_paise = price_paise × seats`.

---

## One-command burst (on-sale stampede)

```bash
./burst.sh https://<your-app>.onrender.com
```

It creates a fresh show, mints user tokens, then fires three waves — a **hot-seat storm** (hundreds
fighting for one seat), **idempotent retries** (same key fired concurrently), and a **general
stampede** across all seats — and prints the outcome distribution and final reconciliation.

Scale it up (the grader-sized run):

```bash
SPREAD_REQUESTS=20000 HOT_REQUESTS=1000 CONC=200 ./burst.sh https://<your-app>.onrender.com
```

Tunables: `SEATS USERS HOT_REQUESTS SPREAD_REQUESTS IDEMPOTENCY_REPLAYS CONC ADMIN_TOKEN PRICE`.

What a healthy run shows: exactly **1** `201` for the hot seat, everyone else `409 seat_taken`,
**0** `5xx`, and `available + held + confirmed == total_seats`.

---

## Observability

- **Health:** `/readyz` (and `/health/readiness`) run `SELECT 1` and return `503` when the DB is
  unreachable — readiness fails closed so a load balancer stops routing to a broken instance.
- **Metrics (`/metrics`, Prometheus):**
  - `reservations_confirmed_total` — seats secured;
  - `reservations_declined_total{reason="seat_taken|per_user_limit|idempotent_replay|idempotency_conflict|transient_conflict"}`;
  - `seats_available{show_id="…"}` — gauge, reconciles with `GET /shows/{id}`;
  - plus JVM + HTTP latency/error histograms from Actuator.
- **Logs:** structured, each line carrying a correlation id. Every request gets an `X-Request-Id`
  (reused if sent, generated otherwise), echoed in the response header and stamped on every log line
  via MDC. Set `LOG_FORMAT=ecs` (or `logstash`) for JSON logs in production.

---

## Deploy (free: Render app + Aiven MySQL)

1. **MySQL (Aiven free):** create a free MySQL service at aiven.io, create a database `seatreserve`,
   and copy the host/port/user/password.
2. **App (Render):** New → Blueprint, point at this repo (it reads `render.yaml`). In the service's
   **Environment**, set:
   - `SPRING_DATASOURCE_URL` = `jdbc:mysql://<host>:<port>/seatreserve?sslMode=REQUIRED`
   - `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
   - `ADMIN_TOKEN` = a secret of your choice
   - `JWT_SECRET` is auto-generated by Render.
3. Deploy. Render builds the Dockerfile from a clean checkout (Maven Central), runs Flyway on boot,
   and health-checks `/readyz`. Confirm `GET /readyz` is `UP`, then run `./burst.sh <live-url>`.

> Free tiers spin down when idle; the first request after a cold start wakes the app — confirm it is
> healthy (and the Aiven DB is awake) before sharing the URL.

---

## Configuration (env vars)

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | local MySQL | JDBC URL. |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `seat` / `seatpw` | DB credentials. |
| `JWT_SECRET` | dev secret | HS256 signing key (≥ 32 bytes). **Set in prod.** |
| `ADMIN_TOKEN` | `admin-local` | required `X-Admin-Token` to create shows. |
| `HOLD_TTL_SECONDS` | `120` | hold lifetime before auto-expiry. |
| `DEFAULT_PER_USER_LIMIT` | `4` | per-user seat limit when a show omits it. |
| `DB_POOL_SIZE` | `40` | HikariCP max pool size. |
| `PORT` | `8080` | HTTP port (Render sets this automatically). |
| `LOG_FORMAT` | *(plain)* | `ecs`/`logstash` for structured JSON logs. |

---

## AI usage

Built with AI assistance (Claude). I directed the architecture and the correctness model; the
assistant drafted boilerplate and docs. Details and the honest "directed vs decided" split are in
[WRITEUP.md](WRITEUP.md).
