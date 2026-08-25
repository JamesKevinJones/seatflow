# Load test results

Real numbers from real runs. Nothing here is estimated or projected; if a figure
is not in this file, it was not measured.

## What was measured

`load/reserve-contention.js` — 1,000 virtual users competing for **100 seats**,
ramping over 70 seconds. Every user repeatedly asks for one randomly chosen seat.
Almost all of them must lose, and the run is only meaningful if the ledger at the
end says exactly 100 seats were taken.

```bash
k6 run load/reserve-contention.js
```

## Environment

Not a benchmark rig. These numbers describe this laptop, not the design's ceiling.

| | |
| --- | --- |
| Machine | Windows 11 laptop, WSL2 Ubuntu |
| Backend | Spring Boot 4.1.1 on Temurin 21, **running inside WSL** |
| Database | PostgreSQL 16 in Docker, same WSL instance |
| Load generator | k6 v0.54.0, same WSL instance |
| Date | 2026-08-25 |

The backend was moved into WSL for these runs. Driving load from WSL to a
Windows-hosted process crosses the Hyper-V bridge and Windows Firewall, which
started dropping connections under load - that measures the network path, not the
application. Same-host removes the variable.

## Run 1 - default pool (`maximum-pool-size: 20`)

| Metric | Value |
| --- | --- |
| Requests | 75,868 |
| Throughput | **1,018 req/s** |
| Reservations succeeded | **100** |
| Reservations conflicted (409) | 75,695 |
| Unexpected errors | 7 (0.009%) |
| Latency median | 79 ms |
| Latency p90 / p95 | 669 ms / 953 ms |
| Latency max | 3.66 s |
| **Seats held at the end** | **100 of 100** |

The seven errors were all `CannotCreateTransactionException` on
`POST /api/v1/reservations` - callers that waited longer than the 3 second
connection timeout for a pooled connection. No request produced an incorrect
outcome; they failed to start rather than doing the wrong thing.

## Run 2 - larger pool (`maximum-pool-size: 60`)

| Metric | Value | vs run 1 |
| --- | --- | --- |
| Requests | 51,474 | -32% |
| Throughput | **679 req/s** | **-33%** |
| Reservations succeeded | **100** | same |
| Unexpected errors | 111 (0.21%) | 16x worse |
| Latency median | 352 ms | 4.5x worse |
| Latency p95 | 1.68 s | 76% worse |
| **Seats held at the end** | **100 of 100** | same |

**Tripling the connection pool made everything worse except correctness.**

That is the interesting result, and it is worth being precise about why. The
bottleneck was never the pool. Every one of those 1,000 users is competing for
one of 100 rows, so the real contention is the row locks inside PostgreSQL. More
connections means more transactions queued on the same locks at the same time -
more waiting, more context switching, and more of them exceeding their timeout.
The pool was not a queue that needed widening; it was acting as admission
control, and removing it let more work in than the database could usefully do.

A single run each on a busy laptop is weak evidence for the exact percentages.
The direction was consistent and the mechanism is sound, but treat the numbers as
indicative rather than a benchmark.

## The result that actually matters

Both runs: **exactly 100 seats held out of 100.** Never 101. Never a seat in two
reservations.

Across the two runs, roughly 127,000 requests fought over 100 seats and 200
reservations were created - exactly the 100 available seats, twice. The test
fails loudly if that number is ever exceeded:

```js
if (held > availability.total) {
  throw new Error(`OVERSOLD: ${held} seats held out of ${availability.total}`)
}
```

Throughput can be tuned. Overselling cannot be un-sold.

## What this does not show

- **One instance.** Multi-instance behaviour is untested; the expiry sweeper's
  advisory lock is written for it but has not been exercised that way.
- **Reservation only.** The payment and booking path is covered by
  `PaymentAndBookingIT`, not by this load test.
- **A laptop.** Shared CPU with the load generator, the database, and a browser.
  Absolute figures would be different on real hardware; the shape of the finding
  would not.
- **Cold start.** No JIT warm-up phase, so early requests are slower than steady
  state.

## If tuning this further

The lesson from run 2 is that the pool is not the lever. Things that would
actually move the numbers:

- Reject requests for seats already known to be taken before opening a
  transaction, which is what the Phase 5 Redis cache is for.
- Shorten the transaction: the reservation insert and the hold could be one
  statement rather than two round trips.
- Measure PostgreSQL's own lock waits (`pg_stat_activity`, `pg_locks`) rather
  than inferring from client latency.
