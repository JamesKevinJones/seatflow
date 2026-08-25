# Project State

> Updated at the end of every session, by whichever agent was driving.
> Keep it under a page. This is a baton, not a diary.

**Last updated:** 2026-08-25 by claude-code

## Where things stand

**Every phase of the original brief is done, plus multi-instance support.**

`docker compose up --build` brings up PostgreSQL, Redis, Kafka, the backend and
an nginx-served frontend, and the whole product works from
`http://localhost:8088`: browse, watch the seat map update live, hold seats
against a countdown, pay, get a booking reference.

`docker compose up --build --scale backend=3` runs the same thing horizontally.

Everything below was measured, not assumed:

- `./mvnw verify` in WSL: **3 surefire + 27 failsafe, 0 failures**
- **Load: 1,000 users against 100 seats - 1,018 req/s, exactly 100 sold.** See
  `load/RESULTS.md`, including a tuning attempt that made things worse
- Shell suites: auth 15, catalogue 29, reservations 21, checkout 22
- **Against the containerised stack**: checkout 22, all services healthy
- **Kafka stopped entirely**: checkout still 22 of 22, 4 bookings, 0 seats sold
  twice, 4 messages left pending; backlog drained in ~9s on restart with
  `attempts` peaking at 3
- **Two instances**: a hold placed through the load balancer reached STOMP
  clients on *both*, with the same `seq`; a booking made on backend-2 was
  confirmed by the consumer on backend-1, once; exactly one instance reported the
  expiry sweep; 40 requests split 25/15
- **Redis stopped mid-flight**: reservations still 201, 4 seats correctly held,
  0 sold twice - at 3.05s each against 42ms healthy, and the fan-out
  re-subscribed on its own when Redis came back
- **Redis never reachable at all**: `RedisUnavailableIT` starts the application
  against a closed port and still sells seats

## In progress

Nothing half-done.

## The exact next step

No phase is outstanding. The honest options, in the order they add value:

1. **Reject known-taken seats before opening a transaction.** The measured
   bottleneck is row-lock contention, so the lever is doing less work per
   request. This is the one change likely to move the load numbers.
2. **Move the after-commit block off the request thread.** The sequence bump,
   the fan-out publish and the cache eviction all run synchronously on the
   committing thread, which is why a Redis outage costs ~3s per reservation
   instead of nothing. Needs an executor and an answer for ordering, since two
   commits could then be numbered out of order.
3. **Durable consumer deduplication.** `ProcessedMessages` is an in-memory
   bounded set. The correct version writes the message id inside the consumer's
   own transaction - the same argument as the outbox, pointed the other way.
4. **Refresh tokens into an httpOnly cookie.** Currently `localStorage`. Needs a
   backend change and a CSRF story.
5. **Deploy it somewhere.** The compose stack runs on any container host. This
   is the largest gap between the project and a link someone can click.

## Open questions

- **Deployment.** Vercel suits the frontend but cannot host Spring Boot. Free
  container tiers sleep, which makes a shared link cold-start or fail.
- **Infrastructure is still single-node.** The application scales; one database,
  one Redis, one Kafka broker do not. Adding instances does not address that.
- **The Kafka container has no volume.** Restarting the broker loses its log, so
  messages already marked published are not redelivered. The durable record is
  the `outbox` table.
- **A Redis outage costs ~3s per reservation.** Three Redis calls on the
  after-commit path, each waiting out the 1s timeout. Correctness is unaffected;
  it was 6.05s at the original 2s. It is not tuned lower because 500ms broke the
  build - Lettuce applies the command timeout to connection initialisation too.
  The real fix is moving after-commit work off the request thread.
- **`docs/API.md` does not exist** - the OpenAPI document at `/docs` is generated
  instead, which is better but only available when the app is running.

## Known traps

Ordered by how much time they cost.

- **WSL terminates seconds after the last command exits**, taking the databases
  with it. Hold it open with a genuinely backgrounded `wsl -e bash -lc
  "sleep infinity"` and check `ps -eo cmd | grep 'sleep infinity'` actually shows
  it - a hidden `Start-Process` did **not** survive, and the symptom was the
  whole stack silently restarting between commands and nginx returning 502.
- **The WSL2 localhost relay sometimes stops forwarding.** `curl 127.0.0.1:8088`
  returns 000 from Windows and 200 from inside WSL. Run the shell suites from
  inside WSL instead; they resolve `python3` or `python` so both work.
- **Do not put shell syntax inline in a PowerShell `wsl -e bash -lc "..."`.**
  PowerShell eats `$(...)`, `$_`, and `seq`. Write a script file and run
  `wsl -e bash /mnt/c/.../script.sh`.
- **nginx returns 200 with `index.html` for unmatched paths.** A request to an
  unproxied path such as `/actuator/prometheus` looks like it succeeded. Check
  the body, not the status.
- **Use `127.0.0.1`, not `localhost`, in healthchecks and configs.** It bit twice
  in different places: the WSL port relay and the nginx container both listen on
  IPv4 while `localhost` resolves to `::1` first.
- **nginx resolves an `upstream` block once, at startup.** With `--scale` it
  would pin every request to one replica and look like it was working. The
  hostname must be in a variable with a `resolver` directive.
- **Redis pub/sub has no buffering.** A message published before the subscriber
  is registered is dropped. Any test of the fan-out must wait for the
  subscription to be live, on a separate channel - probing on the real one pushes
  a malformed message past the production subscriber and makes clean runs look
  broken.
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
  surefire, 27 failsafe.
- **Never publish to Kafka inside a business transaction.** Record to the
  `outbox` table instead. `OutboxRecorder` is `Propagation.MANDATORY` so the
  mistake throws rather than silently reintroducing the dual write.
- **The outbox relay must not track a high-water mark.** Sequence ids are
  assigned at INSERT and rows appear at COMMIT, so a watermark steps over rows
  committed out of order. Ask what is unpublished.
- **A new cache needs its value type registering** in `CacheConfig`, or a generic
  serializer returns `LinkedHashMap` and the failure lands outside the
  `CacheErrorHandler`.
- **Anything that changes seat state must publish `SeatStatusChanged`**, or both
  the live map and the cache go stale.
- **Anything scheduled needs an advisory lock, anything queue-like needs
  `SKIP LOCKED`, and no client-visible counter may live in a field.** All three
  failures are silent on one instance. `MultiInstanceIT` is where they get
  caught.
- **Fixtures must go through the API, not the tables.** Faked identifiers were
  rejected by V4's and V5's foreign keys when they arrived.
- **An entity with an assigned id makes Spring Data issue an UPDATE**, so
  `@PrePersist` never runs and audit columns stay null.
- **springdoc 3.x for Boot 4**; the 2.x line will not start.
- **Boot 4 is not Boot 3**: `-webmvc` not `-web`, Jackson is `tools.jackson`,
  and the Kafka starter is `spring-boot-starter-kafka`.
- **Never test concurrency on H2**, and never mark a concurrency test
  `@Transactional`.
- **Do not remove the `status` predicate** from `EventSeatRepository.tryHold`.
  It is the entire double-booking defence, and the query still looks correct
  without it.
