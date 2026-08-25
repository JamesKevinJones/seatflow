# Project State

> Updated at the end of every session, by whichever agent was driving.
> Keep it under a page. This is a baton, not a diary.

**Last updated:** 2026-08-25 by claude-code

## Where things stand

**Phases 0 through 9 are done. The product works end to end and the guarantee it
exists for is proven under load.**

A visitor can browse events, open a seat map that updates live as other people
take seats, choose up to eight, sign in, hold them against a ten-minute
countdown, pay, and get a booking reference. A declined card keeps the hold. A
seat lost to someone else is named in the error and greyed out while the rest of
the selection survives.

Everything below was measured, not assumed:

- `./mvnw verify` in WSL: **3 surefire + 15 failsafe, 0 failures**
- **Load: 1,000 users against 100 seats - 75,868 requests at 1,018 req/s, and
  exactly 100 seats sold.** Full numbers, including a tuning experiment that made
  things worse, in `load/RESULTS.md`
- Shell suites: auth 15, catalogue 29, reservations 21, checkout 22 - all passing
- Browser: live seat map moved 155 to 150 available when another customer took
  five seats, with no refetch on the watching page
- **Redis stopped mid-flight: seat map still 200, reservation still 201**

Remaining phases: **8 (Kafka)** and **10 (Docker, metrics, README, diagrams)**.

## In progress

Nothing half-done.

## The exact next step

Pick one:

- **Phase 10** is the higher-value one now. The system works; what is missing is
  the packaging that lets someone else run it - a compose file that starts the
  whole stack, Actuator metrics for the counters named in the brief
  (`reservation_conflicts_total` and friends), a real README, and an architecture
  diagram. This is also what a reader looks at first.
- **Phase 8 (Kafka)** would add `BookingConfirmed` and `ReservationExpired`
  topics with in-process consumers. Do it through a **transactional outbox**:
  `SeatStatusChanged` already fires after commit, but publishing to Kafka inside
  the booking transaction is a dual write that eventually loses events or emits
  ones for transactions that rolled back. V6 was reserved for the outbox table.

## Open questions

- **Deployment.** Vercel suits the frontend but cannot host Spring Boot; the
  backend needs a container host plus managed Postgres and Redis. Free tiers
  sleep, which makes a shared link cold-start or fail.
- **CORS is still unconfigured** - the Vite dev proxy hides it, and a deployed
  frontend on another origin will need it. The WebSocket endpoint likewise
  currently allows any origin pattern.
- **Single instance only.** The WebSocket broker is Spring's in-memory one and
  the per-event sequence counter is a local `AtomicLong`; a second instance would
  broadcast only to its own clients and restart the sequence. The expiry
  sweeper's advisory lock is written for multi-instance but has never been run
  that way.
- **Token storage** is localStorage; an httpOnly refresh cookie needs a backend
  change.
- **Redis timeout is 2s**, so a Redis outage adds two seconds to the first
  request that tries the cache. Lowering it would degrade faster.

## Known traps

Ordered by how much time they cost.

- **WSL terminates seconds after the last command exits**, taking PostgreSQL and
  Redis with it. Start `wsl -e bash -lc "sleep infinity" &` first.
- **CSS transitions freeze when the Browser pane is hidden**, so
  `getBoundingClientRect` returns mid-transition values. Inject
  `*{transition:none !important}` before measuring layout.
- **Load tests must run with the backend inside WSL.** Driving load from WSL at a
  Windows-hosted process gets throttled by Windows Firewall and measures the
  bridge, not the app.
- **Do not tune the connection pool up to fix latency.** It was measured: 20 to
  60 made throughput 33% worse. The contention is PostgreSQL row locks.
- **Sizing a concurrency-test thread pool below the task count deadlocks the
  build** - one thread per caller.
- **`mvn test` silently skips every `*IT`** and still prints BUILD SUCCESS. Use
  `mvn verify`. Expected: 3 surefire, 15 failsafe.
- **A new cache needs its value type registering** in `CacheConfig`. A generic
  serializer returns `LinkedHashMap` and the failure lands *outside* the
  `CacheErrorHandler`, turning a cache problem into a 500.
- **Anything that changes seat state must publish `SeatStatusChanged`**, or the
  live map and the cache both go stale.
- **Fixtures must go through the API, not the tables.** Faked
  `held_by_reservation_id` and `booking_id` values were rejected by V4's and V5's
  foreign keys when they arrived.
- **An entity with an assigned id makes Spring Data issue an UPDATE, not an
  INSERT**, so `@PrePersist` never runs and audit columns stay null.
- **`npm --prefix <path> run dev`**, not `npm run dev --prefix <path>`.
- **`.claude/launch.json` must use the 8.3 short path**; that is also why
  `server.fs.strict` is off.
- **The WSL port relay is IPv4-only**; configs name `127.0.0.1` deliberately.
- **A catch-all `@ExceptionHandler(Exception)` swallows security exceptions.**
- **Boot 4 is not Boot 3**: `-webmvc` not `-web`, Jackson is `tools.jackson`.
- **Never test concurrency on H2**, and never mark a concurrency test
  `@Transactional`.
- **Do not remove the `status` predicate** from `EventSeatRepository.tryHold`.
  It is the entire double-booking defence, and the query still looks correct
  without it.
