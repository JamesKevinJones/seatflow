# Resume material

Every number here is measured and reproducible from this repository. Nothing is
rounded up, and nothing describes work that was not done.

## The bullets

Pick three or four. They are ordered by how much they differentiate you — the
first one is the project.

- **Built a ticket reservation platform that provably cannot double-book a seat**,
  using a single atomic conditional `UPDATE` in PostgreSQL rather than
  application locks; verified with a 200-thread contention test where exactly one
  caller wins and 199 fail cleanly, and a 1,000-user load test that sold exactly
  100 of 100 seats at 1,018 req/s.

- **Diagnosed a counter-intuitive performance result under load**: tripling the
  HikariCP pool cut throughput 33% (1,018 to 679 req/s) and raised p95 from 953ms
  to 1.68s, because the real contention was PostgreSQL row locks, not connection
  availability — the pool was acting as admission control.

- **Designed the caching and messaging layers so an outage degrades latency, not
  correctness.** Redis holds read models only; killing it mid-request leaves
  reservations and bookings working, verified by stopping the container while
  serving traffic.

- **Made overselling structurally impossible** with a partial unique index over
  confirmed bookings, tested by bypassing the application entirely and inserting
  directly against the database.

- **Split a payment flow across transaction boundaries** so a third-party call
  never holds a pooled connection, with a pre-recorded `PROCESSING` row making a
  mid-flight crash recoverable and a partial unique index preventing double
  charges.

- **Shipped a live seat map over STOMP/WebSocket** with sequence-numbered deltas
  and gap detection, broadcasting only after transaction commit so a rolled-back
  hold is never announced.

## One-line project summary

> High-concurrency seat reservation platform (Java 21, Spring Boot 4, PostgreSQL,
> Redis, React). Proves no double-booking under 1,000 concurrent users via
> atomic conditional updates and database constraints, with load-test evidence.

## Talking points for an interview

The value of this project is not the stack; it is being able to answer follow-up
questions precisely.

**"Why not just use a lock?"**
A Redis lock moves the source of truth off the database and introduces the
classic failure: the TTL expires mid-transaction and two holders both believe
they own the seat. A row lock via `SELECT ... FOR UPDATE` works, but holds locks
for the whole transaction and can deadlock when two multi-seat requests lock in
opposing orders. The conditional `UPDATE` collapses check and act into one
statement, so the database arbitrates and the affected-row count is the answer.

**"Why does the status predicate matter?"**
Under READ COMMITTED, a blocked `UPDATE` re-evaluates its `WHERE` clause against
the newly committed row version. That re-check is what makes the loser match zero
rows. Remove the status predicate and the query still looks correct but silently
becomes last-writer-wins.

**"How do you know it works?"**
A test that is not `@Transactional`, running against real PostgreSQL through
Testcontainers, not H2 — H2 does not reproduce those locking semantics, so an H2
run would pass and prove nothing. 200 threads on a `CountDownLatch`, released
together, asserting exactly one success and exactly one RESERVED row.

**"What happens when the payment is slower than the hold?"**
The hold is extended before the gateway is called, and the confirmation step
still requires the hold to be live and owned by that reservation. If it lapsed,
the update matches nothing, the count comes up short, and the booking rolls back
rather than charging for seats someone else now has.

**"What would you do differently at scale?"**
The measured bottleneck is row-lock contention, so the lever is doing less work
per request, not more connections. Rejecting requests for known-taken seats
before opening a transaction is the next step. Multi-instance also needs a real
STOMP broker relay and a shared sequence source, both of which are currently
single-instance by design and documented as such.

## What to avoid claiming

- It is not deployed. Vercel can host the frontend but not a JVM backend.
- Payment is simulated. The consistency design is real; the charge is not.
- Kafka is not implemented. Say "designed for a transactional outbox" only if
  you are ready to explain why a dual write is the problem.
- The load numbers are from a laptop with the load generator, database, and
  application on one machine. Quote them as evidence of correctness under
  contention, not as a throughput ceiling.
