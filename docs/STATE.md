# Project State

> Updated at the end of every session, by whichever agent was driving.
> Keep it under a page. This is a baton, not a diary.

**Last updated:** 2026-08-25 by claude-code

## Where things stand

**Phases 0 to 7, 9 and 10 are done. Only Phase 8 (Kafka) remains.**

The system is complete and packaged. `docker compose up --build` brings up
PostgreSQL, Redis, the backend, and an nginx-served frontend, and the whole
product works from `http://localhost:8088`: browse, watch the seat map update
live, hold seats against a countdown, pay, get a booking reference.

Everything below was measured, not assumed:

- `./mvnw verify` in WSL: **3 surefire + 15 failsafe, 0 failures**
- **Load: 1,000 users against 100 seats - 1,018 req/s, exactly 100 sold.** See
  `load/RESULTS.md`, including a tuning attempt that made things worse
- Shell suites: auth 15, catalogue 29, reservations 21, checkout 22
- **Against the containerised stack**: checkout 22 and reservations 21 pass
  unchanged, and all four services report healthy
- Metrics move with real traffic: 8 requests, 1 conflict, 2 bookings, 1 failure
- Redis stopped mid-flight: seat map still 200, reservation still 201

## In progress

Nothing half-done.

## The exact next step

**Phase 8, Kafka**, is all that is left of the original plan.

Do it through a **transactional outbox**, not a direct publish.
`SeatStatusChanged` already fires after commit and drives the WebSocket feed and
cache invalidation, but writing to Kafka inside the booking transaction is a dual
write: it eventually loses events, or emits events for transactions that rolled
back. `V6__outbox.sql` is the reserved slot.

1. `outbox` table written in the same transaction as the booking.
2. A relay polls it and publishes to `booking.confirmed`,
   `reservation.expired`, `payment.completed`.
3. Consumers stay in-process for now - the brief is explicit that this
   demonstrates event-driven architecture, not microservices.

If not Kafka, the highest-value remaining work is **making it multi-instance**:
a STOMP broker relay and a shared sequence source. That is the honest gap between
this and something that could actually be deployed behind more than one node.

## Open questions

- **Deployment.** Vercel suits the frontend but cannot host Spring Boot. The
  compose stack is deployable to any container host; free tiers sleep, which
  makes a shared link cold-start or fail.
- **Single instance only.** In-memory STOMP broker, local `AtomicLong` sequence.
  The sweeper's advisory lock is written for multi-instance but never run that
  way.
- **Refresh tokens in localStorage.** An httpOnly cookie needs a backend change.
- **Redis timeout is 2s**, so an outage adds two seconds to the first request
  that tries the cache.
- **`docs/API.md` does not exist** - the OpenAPI document at `/docs` is generated
  instead, which is better but only available when the app is running.

## Known traps

Ordered by how much time they cost.

- **WSL terminates seconds after the last command exits**, taking the databases
  with it. Start `wsl -e bash -lc "sleep infinity" &` first.
- **nginx returns 200 with `index.html` for unmatched paths.** A request to an
  unproxied path such as `/actuator/prometheus` looks like it succeeded. Check
  the body, not the status.
- **Use `127.0.0.1`, not `localhost`, in healthchecks and configs.** It bit twice
  in different places: the WSL port relay and the nginx container both listen on
  IPv4 while `localhost` resolves to `::1` first.
- **CSS transitions freeze when the Browser pane is hidden**, so
  `getBoundingClientRect` returns mid-transition values. Inject
  `*{transition:none !important}` before measuring layout.
- **Load tests must run with the backend inside WSL**, or Windows Firewall
  throttles the bridge and you measure the network.
- **Do not tune the connection pool up to fix latency.** Measured: 20 to 60 made
  throughput 33% worse. The contention is PostgreSQL row locks.
- **Sizing a concurrency-test thread pool below the task count deadlocks the
  build** - one thread per caller.
- **`mvn test` silently skips every `*IT`.** Use `mvn verify`. Expected: 3
  surefire, 15 failsafe.
- **A new cache needs its value type registering** in `CacheConfig`, or a generic
  serializer returns `LinkedHashMap` and the failure lands outside the
  `CacheErrorHandler`.
- **Anything that changes seat state must publish `SeatStatusChanged`**, or both
  the live map and the cache go stale.
- **Fixtures must go through the API, not the tables.** Faked identifiers were
  rejected by V4's and V5's foreign keys when they arrived.
- **An entity with an assigned id makes Spring Data issue an UPDATE**, so
  `@PrePersist` never runs and audit columns stay null.
- **springdoc 3.x for Boot 4**; the 2.x line will not start.
- **Boot 4 is not Boot 3**: `-webmvc` not `-web`, Jackson is `tools.jackson`.
- **Never test concurrency on H2**, and never mark a concurrency test
  `@Transactional`.
- **Do not remove the `status` predicate** from `EventSeatRepository.tryHold`.
  It is the entire double-booking defence, and the query still looks correct
  without it.
