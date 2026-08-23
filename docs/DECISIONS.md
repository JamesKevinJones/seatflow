# Decisions

Append-only. Newest at the top. Never edit an old entry - if it stops being true,
add a new one that supersedes it and say so.

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
