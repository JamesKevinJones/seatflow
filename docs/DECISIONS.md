# Decisions

Append-only. Newest at the top. Never edit an old entry - if it stops being true,
add a new one that supersedes it and say so.

---

## 2026-08-25 - The fan-out subscription must not start with the context

**Context.** Adding the cross-instance seat-update fan-out meant registering a
listener on a `RedisMessageListenerContainer`. Such a container opens its
subscription when the context starts, and if Redis is unreachable at that moment
it throws - which surfaces as `ApplicationContextException: Failed to start bean`
and the application does not start at all.

A cache had become an availability dependency. That is the exact coupling this
project spends most of its effort avoiding, and it was invisible: every
integration test runs with a real Redis container, so nothing could catch it.
It only appeared when the Redis timeout was tightened to 500ms and the entire
suite failed to boot at once - 24 errors, all of them context load failures.

**Decision.** The container is declared with `setAutoStartup(false)` and started
from `SeatUpdateSubscription`, a scheduled task where a failure is a warning and
another attempt in five seconds. `setRecoveryInterval` handles reconnects once it
is running.

**Why not the alternative.** Catching the exception at startup and giving up
would leave the instance permanently unsubscribed after one bad moment - and the
bad moment is exactly a rolling restart, when Redis and the application come back
together. Retrying is what makes the degradation temporary.

**Consequences.** `RedisUnavailableIT` now points the application at a closed
port with no Redis container at all and asserts it starts, sells seats, and
records domain events. It is the only test in the suite that runs without Redis,
which is why it needs its own Testcontainers configuration.

Also worth recording: `RedisMessageListenerContainer.isRunning()` reports its
lifecycle flag, not connectivity - it returns true even when the subscription
behind it failed. The accessor was renamed from `isSubscribed()` to `isStarted()`
after a test asserted the other reading and failed.

---

## 2026-08-25 - The Redis timeout is 1s, not 2 seconds

**Context.** Measured while verifying that multi-instance support had not put
Redis on the correctness path. With Redis stopped, a reservation took **6.05s**,
against 57ms healthy. Correctness was untouched - the seats were held, and
nothing was ever sold twice - but the after-commit path makes three separate
Redis calls (sequence `INCR`, fan-out publish, cache evictions) and each one sat
out the full 2s timeout.

**Decision.** `spring.data.redis.timeout: 1s`, plus an explicit
`connect-timeout: 3s` so a slow TCP connect is not mistaken for an unusable
cache.

500ms was tried first and is too low: Lettuce applies the **command timeout to
connection initialization as well**, and on a loaded machine the handshake needs
longer than that. Every integration test failed to start. The failure was worth
having - it is what exposed the startup-dependency bug above - but the value has
to leave room for a connection to be established.

**Why not the alternative.** Everything Redis is asked for on this path is a
latency optimisation. If it has not answered in half a second, going to
PostgreSQL is faster than continuing to wait - so a long timeout buys nothing and
costs exactly the thing it was meant to protect. A local Redis answers in well
under a millisecond, so this only trips when something is genuinely wrong, and
tripping means using the database, which is always correct.

**Consequences.** Measured on the final build with Redis stopped: a reservation
takes **3.05s against 42ms healthy**, and a seat map read 1.02s against 100ms.
Correct throughout - four seats held, none sold twice - and both figures return
to normal when Redis restarts, with the fan-out re-subscribing on its own.

Three seconds is still slow, and it is now a timeout choice rather than a design
one. The deeper fix is to move the whole after-commit block off the request
thread, which would make it near-zero. Not done: an async listener introduces
ordering and queue-saturation questions, and starting that at the end of the work
without room to verify it properly would be worse than a documented limitation.
Figures are in `docs/VERIFY.md` part 9.

---

## 2026-08-25 - Seat updates fan out over Redis pub/sub, not a STOMP broker relay

**Context.** Spring's simple STOMP broker only knows the sessions attached to its
own JVM. Behind a load balancer that means a user on instance B never hears about
a seat taken on instance A - and nothing errors, nothing logs, and it only
appears once there is a second instance.

**Decision.** Every seat update is published to the Redis channel
`seatflow:seat-updates`. All instances subscribe, including the publisher, and
each delivers to its own STOMP sessions.

**Why not the alternative.** `enableStompBrokerRelay` with RabbitMQ or ActiveMQ
is the heavier and more capable answer, and it means adding a whole piece of
infrastructure for a feed where every message is disposable and a client recovers
from a gap by re-fetching. Kafka was also rejected: consumer groups deliver to
one member, which is the opposite of what a broadcast needs, and its poll
interval would be added to a path where latency is the entire feature.

**Consequences.** The publishing instance does not deliver locally *and* publish
- that would double every message for its own clients. It publishes, and receives
its own message back like everyone else, so there is one code path from "a seat
changed" to "a browser was told". If the publish fails, it delivers locally and
increments `seatflow_fanout_degraded_total`; other instances' clients recover
through the sequence gap.

---

## 2026-08-25 - The broadcast sequence is a Redis counter, not a database column

**Context.** The per-event `seq` was a local `AtomicLong`. Two instances would
each start at 1, so a browser receiving from both sees 1, 1, 2, 2 - read as a gap
by the client, which then re-fetches the whole seat map on every update.

**Decision.** Redis `INCR` on `seatflow:seq:{eventId}`, with a 12 hour TTL.

**Why not the alternative.** The database version - `UPDATE events SET
seat_version = seat_version + 1` inside the reservation transaction - is durable
and would be strictly ordered. It would also serialise every concurrent hold for
one event behind a single row lock, which is a hotspot deliberately introduced
into the exact path the project exists to keep fast. The counter is not
correctness data and does not belong in that transaction.

**Consequences.** If Redis is unreachable, each instance falls back to a local
counter and clients see gaps - which makes them re-fetch, which is what a gap is
supposed to trigger. An outage costs refetching, never a wrong seat map.

---

## 2026-08-25 - The admin bootstrap takes an advisory lock before reading

**Context.** `AdminBootstrapRunner` checks whether the admin exists and creates
it if not. On a fresh multi-instance deployment every instance reads "absent",
every instance inserts, and all but one hit `uq_users_email_lower`. An
`ApplicationRunner` that throws stops the application, so a bug that cannot
happen on one node takes down most of a cluster on its first boot.

**Decision.** Take `pg_try_advisory_xact_lock` **before** the read. An instance
that cannot get it logs and skips.

**Why not the alternative.** Catching `DataIntegrityViolationException` would
also work, but the exception marks the transaction rollback-only, so the recovery
path cannot then read the row it needs. Locking after the read fixes nothing -
the whole point is that the SELECT and the INSERT must be the same instance's.

**Consequences.** There is a test that fires eight concurrent bootstraps and
asserts one account and no failures. It was confirmed to fail with the lock
removed, so it is testing the fix rather than decorating it.

---

## 2026-08-25 - nginx resolves the backend at request time, not at startup

**Context.** `docker compose up --scale backend=3` makes `backend` resolve to
three addresses.

**Decision.** A `resolver 127.0.0.11 valid=10s` directive and the hostname in a
variable, instead of an `upstream { server backend:8080; }` block.

**Why not the alternative.** nginx resolves an upstream once, at startup, and
keeps the single address it got for the life of the process. Everything would go
to whichever container answered first, the other replicas would sit idle, and
scaling would look like it worked while doing nothing. Measured after the change:
40 requests through nginx split 25/15 across two replicas.

**Consequences.** No sticky sessions for WebSockets, deliberately. A socket stays
on whichever instance answered the handshake and every instance receives every
update, so needing stickiness would mean the fan-out was broken.

---

## 2026-08-25 - Every meter is tagged with the instance

**Decision.** `InstanceIdentity` sets a Micrometer common tag from the container
hostname.

**Why not the alternative.** Untagged, two instances scraped into one Prometheus
job produce a single series whose value flickers between two counters, and
"which instance published those messages" stops being answerable. The hostname is
the id because that is what `docker compose ps` shows.

**Consequences.** Confirmed useful immediately: a booking made on backend-2 was
confirmed by a consumer on backend-1, which is only visible because the two
instances report separately.

---

## 2026-08-25 - Domain events go through an outbox table, never straight to Kafka

**Context.** Phase 8 needs `booking.confirmed`, `reservation.expired` and
`payment.completed` on Kafka. The obvious implementation publishes from inside
`PaymentLedger.settle`.

**Decision.** The event is inserted into an `outbox` table in the same
transaction as the booking. A relay polls the table and publishes afterwards.

**Why not the alternative.** Publishing inside the transaction is a dual write
and has no safe ordering. Send first and a failed commit has told the world about
a booking that never happened; commit first and a crash loses the event with
nothing left to say it was owed. This is not a race that better locking fixes -
it is two systems with two failure modes and no shared commit.

**Consequences.** Delivery is at-least-once, not exactly-once: the relay can die
between a successful send and marking the row published. Consumers carry a
`messageId` and check it before acting. Publishing is no longer synchronous with
the sale, which is the point - Kafka can be down and tickets still sell.

---

## 2026-08-25 - OutboxRecorder uses Propagation.MANDATORY

**Context.** The outbox only works if the insert is in the caller's transaction.
With default propagation, calling it from a non-transactional method silently
opens its own and commits immediately.

**Decision.** `@Transactional(propagation = Propagation.MANDATORY)`.

**Why not the alternative.** That silent case is the dual write again, restored
by accident and invisible in review - the code still says `outbox.record(...)`
and still looks correct. MANDATORY turns it into an exception on the first call.

**Consequences.** Recording from outside a transaction throws
`IllegalTransactionStateException`. There is a test that asserts exactly this,
because the guarantee is otherwise unobservable.

---

## 2026-08-25 - The relay claims with SKIP LOCKED and keeps no cursor

**Context.** A relay needs to pick up unpublished rows without two instances
sending the same one twice.

**Decision.** `SELECT ... WHERE published_at IS NULL ORDER BY id LIMIT n FOR
UPDATE SKIP LOCKED`. No high-water mark, no leader election, no advisory lock.

**Why not the alternative.** A "last id I processed" watermark is the obvious
design and is quietly wrong: ids are assigned at INSERT but rows become visible
at COMMIT, so a transaction can take id 100 and commit after one that took 101.
A relay past 101 would step over 100 permanently. Asking what is unpublished is
immune to commit order. SKIP LOCKED then lets every instance run a relay and take
a disjoint batch, which is what makes this multi-instance-ready with no
coordination.

**Consequences.** The batch is locked for the duration of the Kafka round trip,
so the producer timeouts are tuned down hard - `max.block.ms` 5s rather than the
60s default, `delivery.timeout.ms` 10s rather than 120s. An unreachable broker
must not hold rows locked for a minute.

---

## 2026-08-25 - The payload column is TEXT, not JSONB

**Decision.** `outbox.payload` is `TEXT` holding already-serialized JSON.

**Why not the alternative.** JSONB validates and would allow querying, but it
normalises: keys are reordered and insignificant whitespace dropped. The relay's
job is to publish exactly the bytes that were committed, and with JSONB what
reached Kafka would not be byte-identical to what was recorded. The relay never
parses the payload, so the schema is the consumers' concern.

**Consequences.** A malformed payload is caught by the consumer rather than the
database. Acceptable, because the only writer is `OutboxRecorder` serializing a
sealed record type.

---

## 2026-08-25 - Kafka consumer groups for domain events, not broadcast

**Context.** Both the live seat feed and the domain events fan work out, and it
is tempting to carry both the same way.

**Decision.** Domain events go to Kafka with every instance in one consumer
group, so each message is handled exactly once across the cluster. Seat updates
stay separate.

**Why not the alternative.** They need opposite semantics. A confirmation email
must be sent once no matter how many instances are running; a seat update must
reach every instance, because each holds its own WebSocket subscribers. One
mechanism for both is wrong in whichever direction it is chosen.

**Consequences.** The test profile uses a different group (`seatflow-test`) so a
developer running the suite against a shared broker does not steal the
application's messages.

---

## 2026-08-25 - The Kafka container has no volume

**Decision.** The broker in `docker-compose.yml` stores its log in the container.

**Why not the alternative.** A named volume is the more production-shaped answer
and would survive a restart. It also needs the log dir permissions right for the
image's non-root user, which is a rabbit hole for a demonstration stack.

**Consequences.** Restarting the broker loses its log, which means messages
already marked published are not re-delivered. Stated in the README's Known
limits rather than hidden. The durable record of what happened is the outbox
table; the broker is transport.

---

## 2026-08-25 - Verification scripts resolve python3 or python

**Context.** The shell suites hardcoded `python`. That exists in Git Bash on
Windows and not in WSL Ubuntu, where the interpreter is `python3`.

**Decision.** Each script resolves `command -v python3 || command -v python` once.

**Why not the alternative.** Rewriting the JSON handling in `jq` would be a
larger change to five working scripts for no gain. The real problem was assuming
one environment.

**Consequences.** The suites now run both from the host and from inside WSL. That
mattered immediately: the WSL2 localhost relay was not forwarding, so the only
way to reach the containerised stack was from inside WSL.

---

## 2026-08-25 - Only the frontend port is published

**Context.** The compose stack has four services. The obvious setup publishes
each one's port so they are easy to poke at.

**Decision.** Only nginx is published. PostgreSQL, Redis, and the backend are
reachable only on the internal compose network, and nginx proxies `/api` and
`/ws` to the backend.

**Why not the alternative.** Publishing the backend would mean the browser making
cross-origin requests, which means a CORS policy - and permissive dev CORS has a
habit of surviving into production. Keeping everything same-origin means there is
no policy to get wrong. Publishing the database is simply a way to get it
compromised.

**Consequences.** `/actuator/*` is not reachable from outside either, which is
correct but surprising: nginx serves `index.html` for unmatched paths, so
requesting it returns **200 with an HTML body**. That misleading success cost
time during verification - check the body, not the status.

---

## 2026-08-25 - Container healthchecks use 127.0.0.1, not localhost

**Context.** The frontend container reported unhealthy while serving traffic
perfectly. The healthcheck said "connection refused" against
`http://localhost/`.

**Decision.** Healthchecks name `127.0.0.1`.

**Why not the alternative.** Inside the container `localhost` resolves to `::1`
first, and nginx listens on IPv4 only. This is the same trap as the WSL port
relay, in a different place - which suggests treating bare `localhost` as
suspicious anywhere a health check or a config value is involved.

**Consequences.** An unhealthy container blocks anything with a
`depends_on: service_healthy`, so this failure mode stops the stack rather than
merely looking untidy.

---

## 2026-08-25 - springdoc 3.x, and secrets have no defaults in compose

**Context.** OpenAPI generation needs a springdoc line that targets Boot 4 - the
2.x line is for Boot 3 and will not start. Compose also needs a JWT signing key
and an admin password.

**Decision.** `springdoc-openapi-starter-webmvc-ui:3.1.0`, and compose declares
both secrets with `${VAR:?message}` so the stack refuses to start without them.

**Why not the alternative.** A default signing key that works out of the box is a
guessable signing key in every deployment that forgot to change it. A failed boot
with a clear message is the better outcome, and `.env.example` documents what to
set.

**Consequences.** `docker compose up` fails until `.env` exists. That is the
intended behaviour and the README says so.

---

## 2026-08-25 - Cached values are serialized as their exact type

**Context.** The obvious Redis cache setup uses a generic Object serializer.
With Jackson 3 and no default typing, a cached record comes back as a
`LinkedHashMap` and the caller throws `ClassCastException`.

**Decision.** Each cache is configured with `JacksonJsonRedisSerializer` bound to
the type it holds - `SeatMapResponse` for seat maps, `EventResponse` for event
details.

**Why not the alternative.** `enableUnsafeDefaultTyping()` exists and would work,
but it is named that for a reason, and it would let the cache deserialize
whatever a value claims to be.

**Consequences.** More important than the type error itself: with a generic
serializer the *cache read succeeds* and the cast fails afterwards, so the
`CacheErrorHandler` never sees it and a bad cache entry becomes a 500 from a
healthy endpoint. That would have quietly broken the "Redis cannot take the site
down" property this project claims. Naming the type keeps any deserialization
failure inside the cache read, where it degrades to a database fallback.
Adding a cache means adding its type here.

---

## 2026-08-25 - Broadcast only after commit, and evict before broadcasting

**Context.** Live seat updates are published as Spring application events and
consumed by the notification module.

**Decision.** `@TransactionalEventListener(AFTER_COMMIT)` on both the WebSocket
broadcaster and the cache invalidator, with the invalidator ordered first.

**Why not the alternative.** A plain `@EventListener` fires inside the
transaction, so a hold that then rolled back would still have told every watching
browser the seat was gone - a bug that only appears under the contention that
causes rollbacks. And if the broadcast went first, a client reacting to it would
re-read the seat map and get the stale cached copy the notification existed to
correct.

**Consequences.** Anything that changes seat state must publish
`SeatStatusChanged`, or the map goes quietly stale for everyone watching.

---

## 2026-08-25 - The load test measures the design, not the network

**Context.** The first load run drove k6 from WSL against the backend on Windows.
Most requests failed with `dial: i/o timeout` - Windows Firewall dropping bulk
inbound connections across the Hyper-V bridge.

**Decision.** For load runs, the backend runs inside WSL alongside k6 and
PostgreSQL. Numbers in `load/RESULTS.md` are from that topology.

**Why not the alternative.** Measuring across the bridge produces figures about
the firewall, not the application, and they would look like application failures.

**Consequences.** These numbers are same-host and therefore optimistic about
network latency. They are honest about the thing under test, which is contention
handling.

---

## 2026-08-25 - A bigger connection pool made throughput worse

**Context.** The first 1,000-user run produced 7 errors from pool exhaustion, so
the obvious next step was a larger pool.

**Decision.** Left at 20. Recorded the experiment rather than the assumption.

**Why not the alternative.** Tripling the pool to 60 dropped throughput from
1,018 to 679 req/s, raised p95 from 953ms to 1.68s, and produced 16 times more
errors. Every user is competing for one of 100 rows, so the real contention is
PostgreSQL row locks. More connections just means more transactions queued on the
same locks. The pool was acting as admission control, and widening it let in more
work than the database could usefully do.

**Consequences.** Do not tune the pool up in response to latency without
measuring. The lever that would actually help is doing less work per request -
which is what the Redis read cache is for.

---

## 2026-08-25 - The gateway call sits between two transactions, not inside one

**Context.** Paying involves a call to a third party. The obvious shape wraps the
whole thing in one transaction so it is "atomic".

**Decision.** Three steps: a transaction that locks the hold, extends it, and
records a PROCESSING payment; the gateway call with no transaction and no
database connection held; a transaction that confirms the seats, writes the
booking, and marks the payment SUCCESS. `PaymentService` orchestrates and is
deliberately not transactional; `PaymentLedger` holds the transactional halves.

**Why not the alternative.** A transaction spanning the network call holds a
pooled connection for its entire duration. A slow provider then exhausts the
pool and takes down everything else, including the parts of the site that have
nothing to do with payment.

**Consequences.** A crash between the two transactions leaves a PROCESSING
payment with no booking. That is a recoverable state - the row says what was
attempted - and it is why the payment is written before the charge, not after.
The steps must stay on separate beans: a transactional method calling another on
`this` bypasses the proxy and silently merges the boundaries.

---

## 2026-08-25 - Holds are extended before payment, not after

**Context.** A hold with four seconds left could expire while the gateway is
thinking, and the customer would be charged for seats already back in the pool.

**Decision.** `begin()` pushes `held_until` two minutes out before calling the
gateway. The confirmation step still requires the hold to be live and owned by
this reservation, so if it lapses anyway the update matches nothing, the count
comes up short, and the whole booking rolls back.

**Why not the alternative.** Extending after the charge would be too late, and
skipping the check in `confirmForBooking` would mean a slow payment could book a
seat somebody else had since taken.

**Consequences.** "The payment started in time" is what matters, not "the payment
finished in time". A charge that cannot be booked is marked FAILED with the
reason recorded - in a real system that is also where the refund is issued.

---

## 2026-08-25 - Booking ids are generated by Hibernate, not assigned

**Context.** `Booking` originally set its own id in the factory method, because
`event_seats.booking_id` needs the row to exist before it can point at it.

**Decision.** `@UuidGenerator`. Callers read the id after `saveAndFlush`.

**Why not the alternative.** With an assigned id, Spring Data sees a non-null
identifier, decides the entity is not new, and issues a merge - a SELECT and an
UPDATE instead of an INSERT. `@PrePersist` never runs, so `created_at` stayed
null and the insert failed on a NOT NULL constraint. `Persistable.isNew()` would
also work but adds a transient flag to every entity that wants an assigned id.

**Consequences.** Any future entity with an assigned identifier needs the same
consideration. The symptom is distinctive: an UPDATE where an INSERT was
expected, with the audit columns null.

---

## 2026-08-24 - Conflict reporting happens after rollback, never nested

**Context.** A 409 should name the seats the caller lost. Working that out means
reading committed state, and the obvious places to do it are both wrong.

**Decision.** `SeatsUnavailableException` carries only the request. The seats
that were lost are resolved in `ReservationExceptionHandler`, which runs after
the transaction has rolled back and released its connection.

**Why not the alternative.** Reading inside the failed transaction would see that
transaction's own doomed writes. Reading in a `REQUIRES_NEW` transaction would
take a second pooled connection while still holding the first - at 200
concurrent losers that exhausts the pool and deadlocks, precisely under the load
the feature exists to handle.

**Consequences.** There is a tiny window in which a lost seat is released again
before the follow-up read, so the handler reports zero conflicts. The wording
covers that honestly rather than naming seats that now look free.

---

## 2026-08-24 - The expiry sweeper is for the seat map, not for correctness

**Context.** The obvious design makes a scheduled job responsible for freeing
lapsed holds, which quietly makes that job load-bearing.

**Decision.** The hold query itself treats a lapsed hold as claimable
(`status='RESERVED' AND held_until < now()`), so a seat is reservable the instant
its hold expires whether or not anything sweeps. The sweeper only tidies: it
frees rows so the map looks right and marks reservations EXPIRED. It takes
`pg_try_advisory_xact_lock` so only one instance sweeps, and skips rather than
queues when another holds it.

**Why not the alternative.** A scheduled job can be paused, fail, or lag under
load - exactly when contention is highest. Correctness that depends on it fails
at the worst possible moment.

**Consequences.** `ConcurrentReservationIT` disables the sweeper entirely and
still proves lapsed holds are reclaimable. If that test ever needs the sweeper
running to pass, the separation has been broken.

---

## 2026-08-24 - One thread per caller in the concurrency harness

**Context.** The first version of `ConcurrentReservationIT` used a 64-thread pool
for 200 tasks with a start-gate latch. It hung the build indefinitely.

**Decision.** The pool is sized to the number of callers, and the gate wait is
bounded.

**Why not the alternative.** With fewer threads than tasks, the running workers
block on the gate while the remainder sit in the queue and never reach
`ready.countDown()`. The gate never opens, and `ExecutorService.close()` waits on
threads that can never finish - the harness deadlocks before touching the code
under test, which reads exactly like a hang in the application.

**Consequences.** 200 platform threads per test is heavy but bounded, and the
gate is what makes this a stampede rather than a queue. Do not "optimise" the
pool size back down.

---

## 2026-08-24 - The seat map is dark; everything else is paper

**Context.** The brief asked for something that reads as a commercial booking
product, not a student dashboard. The default answers - cream with a serif
display, or near-black with an acid accent - are what an unspecified prompt
produces, and they look the same whatever the product is.

**Decision.** Two rooms. Browsing, forms, and reading happen on paper: warm
off-white, ink text, brass accent. Choosing a seat happens in the house with the
lights down: available seats glow, taken seats are dark. The header changes tone
with the route.

**Why not the alternative.** A single palette throughout would have been less
code. But the light/dark split is doing semantic work, not decoration - "lit
means you can have it" is the seat legend, and it is the one thing about this
product that is actually distinctive.

**Consequences.** Any new screen has to pick a room. Anything on the reservation
path is house-dark; anything informational is paper.

---

## 2026-08-24 - Vite dev proxy instead of CORS

**Context.** The frontend needs to call the backend. The backend has no CORS
configuration, deliberately deferred since Phase 1.

**Decision.** `server.proxy` maps `/api` to `http://127.0.0.1:8080`, so in
development the browser only ever talks to its own origin and CORS never comes
into it.

**Why not the alternative.** Configuring CORS now would mean writing a policy
that cannot be exercised properly until there is a real deployed origin, and
permissive dev CORS has a habit of surviving into production.

**Consequences.** A real deployment - frontend and backend on different origins -
still needs a CORS policy on the backend. The proxy hides that gap rather than
closing it, and it is recorded as an open question in STATE.md.

---

## 2026-08-24 - fs.strict is off in the Vite dev server

**Context.** The project path contains a space ("Kevin codes"). The Claude Code
preview launcher cannot pass such a path, so it starts the dev server via the 8.3
short form. Vite keeps that spelling for request ids but realpaths its serving
allow list, so index.html failed to match its own allow entry and every request
returned a bare 403.

**Decision.** `server.fs.strict: false`.

**Why not the alternative.** Listing both spellings in `fs.allow` does not work -
entries are realpathed before comparison. Pinning `root` fixes index.html but
breaks the `/@vite/client` URL. Moving the repo out of "Kevin codes" would fight
the workspace convention for one tool's limitation.

**Consequences.** Development server only; `vite build` is unaffected and the
server binds to localhost. Revisit if the project ever moves to a space-free path.

---

## 2026-08-24 - The reservation button calls an endpoint that does not exist

**Context.** Phase 4 was started before Phase 3, so the seat selection screen has
no `POST /api/v1/reservations` to call.

**Decision.** Wire the call to the real endpoint shape from the Phase 0 design,
including the Idempotency-Key header, and let the resulting 404 surface as a
plain message: "Reservations aren't available yet - the booking service isn't
running."

**Why not the alternative.** Stubbing a fake success would make the screen demo
well and hide the fact that the core of the product is missing. A mock would also
have to be found and removed later, and mocks that look like features have a way
of shipping.

**Consequences.** The screen is complete apart from the hold itself and starts
working when Phase 3 lands, with no frontend change beyond deleting the
404-specific copy.

---

## 2026-08-23 - Integration tests are *IT and run under Failsafe

**Context.** `EventSeatGenerationIT` was written, compiled, and reported nothing.
Surefire's default includes are `*Test`, `Test*`, `*Tests`, `*TestCase` - not
`*IT`. `mvn test` skipped the entire class and still printed BUILD SUCCESS.

**Decision.** Added `maven-failsafe-plugin`. Integration tests are named `*IT`
and run in the `integration-test` phase; the proof command is `mvn verify`, never
`mvn test`.

**Why not the alternative.** Renaming them to `*Tests` so Surefire picks them up
would work, but it merges fast unit tests with slow Docker-dependent ones, so
there is no longer a quick check that runs without a daemon.

**Consequences.** A green `mvn test` proves nothing about concurrency, because
those tests are all `*IT`. VERIFY.md states the expected per-plugin test counts,
so "failsafe ran 0 tests" reads as a failure rather than a pass.

---

## 2026-08-23 - Method-security denials need their own exception handler

**Context.** A USER calling an admin endpoint got 500 instead of 403.
`@PreAuthorize` throws `AuthorizationDeniedException` *inside* the controller
invocation, so `@RestControllerAdvice` sees it before Spring Security's
`ExceptionTranslationFilter` does, and the catch-all `@ExceptionHandler(Exception)`
turned it into an internal error.

**Decision.** An explicit `@ExceptionHandler(AccessDeniedException.class)`
returning 403. Filter-level rejections still go through
`ProblemDetailAccessDeniedHandler`.

**Why not the alternative.** Dropping the catch-all handler would fix the
symptom but let genuine internal errors leak stack traces to clients.

**Consequences.** There are now two paths to a 403 - method security via the
advice, and filter security via the handler - which both emit the same problem
type. Any future catch-all advice must keep this ordering in mind.

---

## 2026-08-23 - Admin bootstrap is config-driven and idempotent

**Context.** Registration only ever grants USER, so a fresh database had no way
to reach an admin endpoint. Kevin chose config-driven creation over a seeded
migration or manual SQL.

**Decision.** `AdminBootstrapRunner` reads `seatflow.admin.*` at startup:
creates the account if missing, grants ADMIN if the account exists without it,
does nothing if it already has it. An existing password is never overwritten.
Blank credentials disable it entirely.

**Why not the alternative.** A seed migration would put credentials in version
control permanently and ship them to every environment that runs migrations.
Manual SQL cannot be automated in tests and has to be repeated every time the
database is recreated.

**Consequences.** The local profile carries working dev credentials so a fresh
clone runs with no setup; real environments must set SEATFLOW_ADMIN_EMAIL and
SEATFLOW_ADMIN_PASSWORD. The promotion path also means an existing user can be
made admin by configuration alone.

---

## 2026-08-23 - WSL must be held open with a keep-alive during development

**Context.** The WSL VM terminates within seconds of the last command exiting.
Every `wsl -e ...` invocation was booting a fresh VM, restarting the containers,
and then shutting down again - so by the time Maven had compiled and Spring was
starting, PostgreSQL was already gone. This presented as an intermittent
"Connection refused" that looked like a port-forwarding bug for some time.

**Decision.** Hold a long-lived process open in WSL for the duration of a dev
session: `wsl -e bash -lc "sleep infinity"`, backgrounded. Containers then stay
up and the ports stay forwarded.

**Why not the alternative.** Setting `vmIdleTimeout` in `.wslconfig` is a global
change affecting all of Kevin's WSL usage, including an unrelated n8n container.
The keep-alive is scoped to the session and needs no config change.

**Consequences.** VERIFY.md step 1 starts the keep-alive. A "Connection refused"
from the app almost always means the keep-alive died, not that the config is
wrong - check `wsl -l -v` for STATE=Running before debugging anything else.

---

## 2026-08-23 - Datasource host is 127.0.0.1, not localhost

**Context.** The WSL2 port relay binds IPv4 only. On this machine `localhost`
resolves to `::1` first, so the JDBC driver's first connection attempt goes to an
address nothing is listening on.

**Decision.** `application.yml` defaults to `jdbc:postgresql://127.0.0.1:5432/...`
and Redis host `127.0.0.1`.

**Why not the alternative.** Relying on the driver's IPv6-to-IPv4 fallback works
most of the time, which is worse than failing predictably - it produces
intermittent startup failures that look like flaky infrastructure.

**Consequences.** Both are environment-overridable for Docker deployment, where
service names replace the literal address.

---

## 2026-08-23 - Refresh-token reuse detection uses noRollbackFor

**Context.** The reuse branch revokes every live token for the user and then
throws to reject the request. Under a plain `@Transactional`, the throw rolled
the revocation back, so a replayed token left the whole family usable - the exact
opposite of the check's purpose. Caught by end-to-end verification, not by
reading the code.

**Decision.** `@Transactional(noRollbackFor = AuthExceptions.InvalidRefreshToken.class)`
on `AuthService.refresh`, so the revocation commits while the caller still gets a
401.

**Why not the alternative.** `Propagation.REQUIRES_NEW` on an extracted bean also
works and is arguably more explicit, but it needs a second bean to avoid
self-invocation bypassing the proxy. For a single write in one branch,
`noRollbackFor` is the smaller change.

**Consequences.** Any future write in `refresh` that *should* roll back on that
exception now will not. If one appears, move the revocation into its own
`REQUIRES_NEW` bean instead.

---

## 2026-08-23 - Spring Boot 4.1.1, superseding the 3.x in the original brief

**Context.** The brief specified Spring Boot 3.x. Spring Initializr rejects every
3.x version with HTTP 400; only 4.x is served. 3.5.3 remains on Maven Central but
its OSS support window has closed.

**Decision.** Spring Boot 4.1.1 on Spring Framework 7. Kevin chose this over
hand-pinning an EOL 3.5.3.

**Why not the alternative.** Shipping a 2026 portfolio project on an
out-of-support framework invites the question "why?" in exactly the conversation
the project exists to win.

**Consequences.** Boot 4 renamed starters and moved to Jackson 3
(`tools.jackson.databind`). Boot 3 snippets from training data or blog posts will
not compile unmodified - check imports against the actual classpath rather than
recalling them.

---

## 2026-08-23 - Reservation has four states, not five

**Context.** The original brief listed PENDING, ACTIVE, EXPIRED, CANCELLED,
COMPLETED.

**Decision.** Dropped PENDING. Reservation creation and seat acquisition happen in
a single transaction, so a PENDING row is never externally observable - it would
exist only between two statements inside one uncommitted transaction.

**Why not the alternative.** Keeping a state that can never be read is worse than
not having it: it implies an async acquisition flow that does not exist, and the
first person to see it will write code handling a case that cannot occur.

**Consequences.** If seat acquisition ever becomes asynchronous (a queue in front
of high-demand events), PENDING comes back and this entry gets superseded.
Flagged to Kevin as reversible; he approved the proposal without objecting to the
recommendation.

---

## 2026-08-23 - PostgreSQL is the only arbiter of seat ownership

**Context.** The obvious move for "high concurrency" on a resume is a Redis
distributed lock around the seat.

**Decision.** Seat ownership is decided by a single atomic conditional UPDATE in
PostgreSQL, backed by a unique index on `booking_seats(event_seat_id)`. Redis
holds cache, TTL mirrors, rate limits, and WebSocket fan-out only.

**Why not the alternative.** A Redis lock moves the source of truth off the
database and introduces the classic failure: the lock TTL expires mid-transaction
and two holders both believe they own the seat. It also makes a Redis outage a
correctness incident rather than a latency incident.

**Consequences.** Redis can be killed at any time without any booking becoming
incorrect - and there is a test for that. This inversion is the most defensible
property of the design and the thing worth talking about in an interview.

---

## 2026-08-23 - Atomic conditional UPDATE, not JPA optimistic locking

**Context.** Three defensible options for the seat hold: optimistic locking via
`@Version`, pessimistic `SELECT ... FOR UPDATE`, or a conditional UPDATE.

**Decision.** A single conditional UPDATE with `status` in the WHERE clause. The
affected-row count is the verdict.

**Why not the alternative.** `@Version` costs a read round-trip per seat, only
expresses staleness rather than availability (it cannot say "and not already
BOOKED"), and fails late at flush time. `SELECT ... FOR UPDATE` holds locks for
the whole transaction and can deadlock when two multi-seat requests lock the same
seats in opposing orders.

**Consequences.** The `status` predicate is load-bearing. Removing it to "simplify
the query" silently reintroduces last-writer-wins. This is called out in the
Concurrency doc and in AGENTS.md rule 2.

---

## 2026-08-23 - Containers run in WSL, the app runs on Windows

**Context.** Docker Desktop is deliberately disabled on this machine. The Windows
`docker` CLI still points at the dead `desktop-linux` pipe. Native Docker 29.7.2
with Compose v5.5.0 works inside WSL Ubuntu.

**Decision.** PostgreSQL, Redis, and Kafka run as containers inside WSL. The
Spring Boot app and the Vite dev server run on Windows and connect over
`localhost`, relying on WSL2 port forwarding.

**Why not the alternative.** Running the JVM build inside WSL against
`/mnt/c/...` is slow enough to be miserable, and moving the repo into the WSL
filesystem would split it from the rest of `Kevin codes`.

**Consequences.** WSL2 localhost forwarding is now a load-bearing assumption and
is step 1 of VERIFY.md. If it ever fails, the fallback is publishing ports on
`0.0.0.0` in WSL and connecting to the WSL IP from `wsl hostname -I`.

---

## 2026-08-23 - Spring Security OAuth2 resource server, not jjwt

**Context.** Nearly every Spring Boot JWT tutorial hand-rolls a
`OncePerRequestFilter` around the `jjwt` library.

**Decision.** Use `spring-boot-starter-oauth2-resource-server` with `JwtEncoder`
and `JwtDecoder` beans.

**Why not the alternative.** The filter approach means writing security-critical
parsing and validation code by hand, and it is exactly the pattern a reviewer has
seen a hundred times. The resource-server path is what Spring Boot 3 intends.

**Consequences.** Refresh tokens are opaque and DB-backed rather than JWTs, since
the resource server handles access tokens only. Rotation with reuse detection
lives in the `user` module.

---

## 2026-08-23 - Money is BIGINT cents, never floating point

**Decision.** All monetary columns are `BIGINT` cents mapped to Java `long`.

**Why not the alternative.** `NUMERIC(12,2)` mapped to `BigDecimal` is the more
conventionally "enterprise Java" answer and is also correct, but integer cents is
what real payment systems (Stripe among them) do, and it removes every rounding
conversation before it starts.

**Consequences.** Formatting to a currency string is a presentation concern. Never
introduce a `double` or `float` anywhere near a price.

---

## 2026-08-23 - Bootstrap the backend from Spring Initializr

**Decision.** Generate `backend/` with `curl https://start.spring.io/starter.zip`
rather than hand-writing `pom.xml`.

**Why not the alternative.** A hand-written pom means dependency versions recalled
from training data, which is how you get a build that fails on versions that never
existed. Initializr also ships the Maven Wrapper, which matters because Maven is
not installed on this machine and does not need to be.

**Consequences.** Versions are pinned to whatever Initializr returns on the day
Phase 1 runs, and recorded in AGENTS.md afterwards.
