# SeatFlow

The canonical context file. Claude Code, Antigravity (`agy`), and Codex all read
this — directly or by import. Durable project knowledge lives here; session
status lives in `docs/STATE.md`.

## What this is

SeatFlow is a high-concurrency event ticket reservation platform. Users browse
events, pick seats from a live seat map, hold them for a limited window, pay,
and get a confirmed booking. Admins create venues, seat layouts, and events.

The project exists to solve one problem correctly: **when thousands of people
reach for the same seat at the same moment, exactly one of them gets it, and the
database never ends up inconsistent.** Every architectural decision serves that.
Redis, Kafka, and WebSockets are supporting cast — they are explicitly kept out
of the correctness path. See `docs/CONCURRENCY.md`, which is the most important
document in this repo.

## Stack

Pinned, because a wrong guess here breaks the build.

- **Java 21** (Temurin 21.0.12.1 LTS at `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`)
- **Spring Boot 4.1.1** on Spring Framework 7 / Spring Security 7 — Initializr no
  longer serves 3.x. Boot 4 renamed starters (`-webmvc`, not `-web`;
  `-security-oauth2-resource-server`; per-slice `*-test` starters) and ships
  **Jackson 3** (`tools.jackson.databind`, not `com.fasterxml.jackson.databind`).
  Do not copy Boot 3 snippets without checking imports.
- **PostgreSQL 16** — the single source of truth for booking integrity
- **Flyway** — all schema changes; `ddl-auto` is `validate`, never `update`
- **Redis 7** — cache and coordination only, never an arbiter of correctness
- **Apache Kafka 4.1** (`apache/kafka`, KRaft, no ZooKeeper) — asynchronous
  domain events, published through a transactional outbox. Boot 4 ships
  `spring-boot-starter-kafka`; the Boot 3 line had you depend on `spring-kafka`
  directly. Testcontainers uses `org.testcontainers.kafka.KafkaContainer`.
- **Maven Wrapper** (`mvnw`) — Maven is not installed on this machine and does not need to be
- **React 19 + Vite 8 + TypeScript + Tailwind 4 + TanStack Query 5 + React Router 7**
  — frontend. Tailwind 4 is CSS-first: tokens live in `@theme` inside
  `src/styles/index.css`, there is no `tailwind.config.js`. No Axios (fetch is
  enough), no Zustand (auth is Context, seat selection is page state), no
  component library.
- **Testcontainers 2.0** — integration tests run against real PostgreSQL, Redis
  and Kafka. Module artifacts are `testcontainers-<name>` in the 2.x line.

## Layout

```
backend/src/main/java/com/seatflow/
  common/        config, exception handling, security, shared utils
  user/          accounts, roles, JWT issuance
  venue/         venues, sections, physical seats (static inventory)
  event/         events, event_seats, owns SeatAllocationPort
  reservation/   the concurrency engine — the heart of the project
  booking/       confirmed bookings
  payment/       simulated payment flow
  notification/  WebSocket broadcasts
  messaging/     transactional outbox, Kafka relay, published event contract

Each module follows domain / application / infrastructure / presentation.

backend/src/main/resources/db/migration/   Flyway migrations, V1 upward
infra/                                     docker-compose for Postgres/Redis/Kafka
frontend/                                  Vite app (Phase 4)
load/                                      k6 scenarios (Phase 9)
```

## Rules

1. **Never emit the section-sign character (U+00A7, the double-S legal symbol)**
   in any generated output — not in chat replies, code, comments, commit
   messages, docs, or identifiers. Write "Section 4" or "see Concurrency" in
   plain words instead. This applies to every agent working in this repo.
2. **PostgreSQL is the source of truth.** Redis may go down without any booking
   becoming incorrect. If a change makes correctness depend on Redis, it is wrong.
3. **Never `@Transactional` a concurrency test.** A test-managed transaction
   hides the exact behaviour under test.
4. **Never test concurrency on H2.** H2 does not reproduce PostgreSQL row-lock
   and predicate re-check semantics, so an H2 test is green and meaningless.
   Testcontainers with real PostgreSQL, always.
5. **Schema changes go through a new Flyway migration.** Never edit an applied
   migration, never rely on Hibernate auto-DDL.
6. **Money is `BIGINT` cents mapped to `long`.** No floating point, anywhere.
7. **Broadcast only after commit** (`@TransactionalEventListener(AFTER_COMMIT)`).
   Publishing before commit shows clients a state that may roll back. Anything
   that changes seat state must publish `SeatStatusChanged`, or both the live
   map and the Redis cache go stale.
8. **Redis is never allowed to matter.** It holds read models, the broadcast
   sequence counter, and the seat-update fan-out channel - none of it
   authoritative. Killing it must leave reservations, expiry, and booking
   working; the sequence falls back to a local counter and the fan-out falls
   back to local delivery, so clients refetch more and nothing is wrong. There
   is a check in `docs/VERIFY.md` part 9. A new cache must register its value
   type in `CacheConfig`, or deserialization failures escape the
   `CacheErrorHandler`.
9. **Fixtures go through the API, not the tables.** Faked identifiers have twice
   been rejected by foreign keys added in a later migration.
10. **Kafka is never allowed to matter either.** Domain events are recorded to
    the `outbox` table inside the business transaction and relayed afterwards.
    Never publish to a broker from inside a transaction - that is a dual write
    with no safe ordering. `OutboxRecorder` is `Propagation.MANDATORY` to make
    the mistake fail loudly. Stopping the broker must leave the checkout suite
    passing; there is a check for this in `docs/VERIFY.md` part 12.
11. **Consumers must be idempotent.** Delivery is at-least-once. Anything with a
    side effect that is not naturally repeatable checks `messageId` against
    `ProcessedMessages` before acting.
12. **Assume more than one instance.** The system runs behind
    `--scale backend=N`. Anything scheduled takes an advisory lock, anything
    queue-like uses `SKIP LOCKED`, anything broadcast goes through the Redis
    fan-out channel rather than `SimpMessagingTemplate` directly, and no counter
    that clients depend on may live in a field. These failures are all silent on
    one node - `MultiInstanceIT` is where they get caught.
13. Match the surrounding code. Do not add dependencies without asking. Run the
    checks in `docs/VERIFY.md` before reporting work as done.

## Commit rules

Never add Claude, or any AI tool, as a co-author or commit attribution.
Recruiters read the Contributors list.

## Read these too

- `docs/STATE.md` — where we stopped, what is next
- `docs/DECISIONS.md` — why things are the way they are
- `docs/CONCURRENCY.md` — the double-booking problem and its solution
- `docs/SCHEMA.md` — tables, constraints, and what each guarantees
- `docs/ARCHITECTURE.md` — module boundaries and request flow
- `docs/VERIFY.md` — how to prove a change works

## Don't touch

- `backend/.mvn/wrapper/` — generated by Spring Initializr, leave as shipped.
- Applied Flyway migrations — superseded by new ones, never edited in place.
