# Decisions

Append-only. Newest at the top. Never edit an old entry - if it stops being true,
add a new one that supersedes it and say so.

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
