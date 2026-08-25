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

- **Eliminated a dual write between PostgreSQL and Kafka using a transactional
  outbox**, so a domain event and the booking it describes share one commit;
  the relay claims work with `FOR UPDATE SKIP LOCKED` and tracks no cursor,
  because sequence ids are assigned at INSERT but rows appear at COMMIT. Proved
  it is off the correctness path by stopping the broker entirely and running the
  full checkout suite: 22 of 22 passed, no seat sold twice, and the backlog
  drained in 9 seconds when the broker returned.

- **Took a single-instance application horizontal**, finding and fixing five
  failure modes that are silent on one node: per-instance WebSocket broadcasts,
  a per-instance sequence counter, a startup race that would have crashed every
  replica but one, an nginx upstream that resolves once and pins all traffic to
  a single container, and — found by tightening a timeout until the test suite
  broke — a Redis listener that made the cache an *availability* dependency, so
  an unreachable Redis stopped the application from starting at all.

## One-line project summary

> High-concurrency seat reservation platform (Java 21, Spring Boot 4, PostgreSQL,
> Redis, Kafka, React). Proves no double-booking under 1,000 concurrent users via
> atomic conditional updates and database constraints, with load-test evidence.
> Domain events published through a transactional outbox; runs horizontally
> behind a load balancer.

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

**"Why an outbox rather than just publishing to Kafka?"**
Publishing inside the transaction is a dual write and has no safe ordering. Send
first and a failed commit has told the world about a booking that never happened;
commit first and a crash loses the event with nothing left to say it was owed.
It is not a race that better locking fixes - it is two systems with two failure
modes and no shared commit. Writing the event to a table makes it one system and
one commit; a relay moves it afterwards. The cost is at-least-once delivery,
which is why every message carries an id and consumers check it before acting.

**"What broke when you ran a second instance?"**
Five things, none of which logged an error. Spring's simple STOMP broker only
knows its own JVM's sessions, so a user on instance B never heard about a seat
taken on instance A. The sequence counter was a local `AtomicLong`, so clients
saw 1, 1, 2, 2 and refetched the whole map on every update. The admin bootstrap
had every instance read "absent" and insert, so all but one hit the unique index
and failed to start. And nginx resolves an `upstream` block once at startup, so
every request went to one replica while scaling appeared to work.

The fifth is the one I'd actually talk about. Adding the cross-instance fan-out
put a listener on a Redis container that opens its subscription during context
refresh — so an unreachable Redis threw and the application would not start. A
cache had become an availability dependency, which is the exact coupling the
whole design avoids, and no test could see it because every integration test runs
with a real Redis. It only appeared when I tightened the Redis timeout and all 24
integration tests failed to boot at once. There is now a test that points the app
at a closed port and asserts it starts and still sells seats.

**"What would you do differently at scale?"**
The measured bottleneck is row-lock contention, so the lever is doing less work
per request, not more connections. Rejecting requests for known-taken seats
before opening a transaction is the next step. The application scales
horizontally now, but its infrastructure does not: one database, one Redis, one
Kafka node, each a single point of failure that more application instances do
not address.

## What to avoid claiming

- It is not deployed. Vercel can host the frontend but not a JVM backend.
- Payment is simulated. The consistency design is real; the charge is not.
- Kafka is implemented, but as a single node with no volume and in-process
  consumers. Say "event-driven within a modular monolith", not "microservices" -
  splitting the booking transaction across a network boundary would destroy the
  single-commit guarantee the whole design rests on.
- Consumer deduplication is an in-memory bounded set, not a durable one. Say so
  before someone asks.
- It has been run on two instances and verified, not operated on two instances.
  There is no rolling deploy, no session draining, and no chaos testing.
- The load numbers are from a laptop with the load generator, database, and
  application on one machine. Quote them as evidence of correctness under
  contention, not as a throughput ceiling.
