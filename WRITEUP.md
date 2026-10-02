# WRITEUP — Seat Reservation at Scale

The whole design pushes every correctness decision into **one atomic SQL step**, so correctness does
not depend on application-level timing. A single MySQL (InnoDB) instance is the system of record.

---

## 1. The atomic decision — no double-sell

**Mechanism:** a conditional `UPDATE` guarded on the current seat state, backed by a
`UNIQUE(show_id, seat_no)` constraint.

```sql
UPDATE seats
   SET status = 'held', held_by = :uid, reservation_id = :rid,
       hold_expires_at = NOW(6) + INTERVAL :ttl SECOND
 WHERE show_id = :sid AND seat_no = :seat AND status = 'available';
```

The caller inspects the affected-row count:
- **1 row** → this transaction won the seat.
- **0 rows** → the seat was already `held`/`confirmed` → a clean `409 seat_taken`.

**Why it is race-free.** `UPDATE … WHERE status='available'` takes an exclusive row lock and
evaluates the predicate against the current committed row. Only one transaction can transition a
given row out of `available`; any concurrent updater either blocks until the winner commits and then
matches 0 rows, or (under READ COMMITTED's semi-consistent read) sees the new `held` version and
skips. There is no read-then-write window — the check and the write are the same statement — so the
classic "is A12 free? ok, take it" double-sell is impossible. The `UNIQUE(show_id, seat_no)`
constraint additionally makes two rows for the same physical seat impossible.

**Why not a plain `SELECT ... FOR UPDATE` then `UPDATE`?** That also works, but the conditional
`UPDATE` is one round trip and expresses the invariant directly in the predicate. I kept `FOR UPDATE`
only where I genuinely need to serialize a *scalar* decision (the per-user counter, below).

**Multi-seat & deadlock avoidance.** A request for `["A12","A13"]` is **all-or-nothing** in one
transaction. Two things prevent deadlock between overlapping multi-seat requests:
1. seats are always claimed in **sorted order**, so every transaction acquires seat row locks in the
   same global order — no lock-ordering cycle between requests;
2. the per-user counter row is locked **after** the seats (consistent "seats → counter" order, which
   the background sweeper also follows).
Any residual contention (e.g. sweeper vs. a reserve) that InnoDB resolves by aborting one transaction
as a deadlock victim is caught and **retried** (bounded, with tiny backoff). If retries are somehow
exhausted it becomes a `409 transient_conflict`, never a `5xx` — and because the aborted transaction
rolled back, no seat was sold. That is how the service holds **zero 5xx** under the storm.

Isolation is **READ COMMITTED** (InnoDB defaults to REPEATABLE READ). The conditional update is
correct at both levels, but READ COMMITTED avoids gap locks and sharply reduces deadlocks under the
hot-seat stampede.

---

## 2. Idempotency — exactly once

**Where the key lives:** every reservation row carries `(user_id, idempotency_key)` with a
`UNIQUE(user_id, idempotency_key)` constraint, plus a `request_fingerprint = SHA-256(show_id | sorted
seats)`.

**How exactly-once is enforced:**
- The reserve transaction's first write is the reservation `INSERT`. A concurrent retry with the same
  key collides on the unique index: the second `INSERT` blocks until the first commits, then fails
  with a duplicate-key error. The service catches it, loads the original reservation, and returns it.
- An ordinary (sequential) retry is short-circuited even earlier by a pre-check
  (`findByUserAndKey`) before opening the transaction.

So the same key **reserves once**; every retry returns the original reservation (`200`), and the
retry moves nothing extra (no extra seat, no extra charge).

**Same key, different body:** on replay we compare the stored fingerprint with the incoming one. A
mismatch (different seats under the same key) is rejected with `409 idempotency_conflict` — we never
silently serve a different result for a reused key.

---

## 3. Holds & expiry

A reserve creates a **time-boxed hold**: the seat goes `available → held` with
`hold_expires_at = now + HOLD_TTL_SECONDS` (default 120s), and the reservation is returned with its
`expires_at`. The lifecycle:

- `POST /reservations/{id}/confirm` (owner) → `held → confirmed` (simulates post-payment finalize).
  If any seat of the hold already expired, the whole confirm fails `409 hold_expired`.
- `POST /reservations/{id}/cancel` (owner) → releases the reservation's seats back to `available`.
  It is scoped to `reservation_id`, so it can **never** resurrect a seat confirmed to someone else.
- A background **sweeper** (every 5s, one transaction) returns expired holds to `available`
  (`WHERE status='held' AND hold_expires_at < NOW(6)` — `confirmed` seats are untouched), marks those
  reservations `expired`, and then **recomputes the per-user counters directly from the seats table**.
  That recompute is the self-healing step: it erases any counter drift left by rare confirm/expiry
  races, so the per-user limit stays accurate without a perfectly-synchronized counter.

A released seat is immediately re-bookable by the same conditional `UPDATE`.

---

## 4. Per-user limit under concurrency

`show_user_counter(show_id, user_id, held_count)` holds a user's held+confirmed count for a show. The
reserve transaction does `SELECT held_count ... FOR UPDATE`, checks `current + requested ≤ limit`, and
only then bumps it. The row lock **serializes a single user's concurrent reserves for that show**, so
a user firing 10 parallel reserves on a `limit=4` show ends with at most 4 held — while other users
are never blocked (they lock different rows). Cancels and expiries decrement/recompute the counter.

---

## 5. Consistency vs availability under a partition (CAP)

This is a **CP** design. The single source of truth is one MySQL instance; the invariant "a seat is
sold at most once" is non-negotiable, so under a network partition between the app and the database we
choose **consistency over availability**: readiness fails closed (`/readyz` → 503), the app stops
accepting reservations, and no seat is sold rather than risk a split-brain double-sell. Recovery is
automatic once the DB is reachable again. If I needed higher write availability I would shard by
`show_id` (each show is an independent contention domain) rather than weaken the per-seat guarantee.

---

## 6. Observability — what would page me at 2am

- **Readiness red / DB unreachable** (`/readyz` 503) — the service can't sell anything; page
  immediately.
- **Any `5xx`** — the correctness contract says declines are 4xx; a 5xx means a bug or an
  unhandled transient, so a non-zero `5xx` rate pages.
- **Reconciliation drift** — `seats_available` gauge vs `GET /shows/{id}` counts;
  `available + held + confirmed ≠ total_seats` would mean a leak in the state machine.
- **Sweeper stalled** — rising `held` with no matching confirms and no expiries means holds aren't
  being reclaimed (seats silently going unsellable).
- **Latency / pool saturation** — HTTP p99 and Hikari pool-wait climbing is the early warning before
  timeouts turn into declines.

Every log line carries the request's correlation id (`X-Request-Id`), so one bad request is traceable
end-to-end.

---

## 7. AI usage — directed vs decided

AI (Claude) was used, as the exercise invites.

**I decided (the parts that are genuinely mine):**
- the correctness model: conditional `UPDATE` as the atomic decision, `UNIQUE` constraints for
  idempotency and seat identity, sorted-order claiming + counter-after-seats lock order for
  deadlock-freedom, READ COMMITTED, the hold/confirm/cancel/expiry state machine, CP posture;
- the all-or-nothing partial-request choice and the same-key-different-body rule;
- the observability signals above.

**AI did (directed by me):** drafted the Spring Boot boilerplate, repositories, DTOs, the burst
script, the Dockerfile/compose, and this document from my design notes; suggested the counter-recompute
self-heal. I reviewed and adjusted every file — e.g. the lock-ordering and the transient-retry →
`409` mapping that keeps 5xx at zero are deliberate choices I verified against the concurrency test.

---

## 8. What I'd do next

- **Hot-seat sharding / queueing** — for a truly viral single seat, front the DB with a short Redis
  queue or per-seat token so most losers are rejected before touching MySQL.
- **Payments via an outbox** — confirm would enqueue a payment intent (transactional outbox) rather
  than finalize inline, with reconciliation against the provider.
- **Horizontal scale** — the app is stateless; scale replicas behind a load balancer and shard the DB
  by `show_id`.
- **Rate limiting & abuse controls** per user/IP at the edge.
- **Richer metrics** — per-reason latency histograms, hold-age distribution, and alerting rules.
