# SeatFlow

A high-concurrency event ticket reservation platform.

Users browse events, pick seats from a live seat map, hold them for a limited
window, pay, and receive a confirmed booking. Admins manage venues, seat layouts,
events, and pricing.

## The actual problem

When 10,000 people reach for 100 seats at the same instant, exactly one person
gets each seat, and the database never ends up inconsistent.

That is the engineering problem this project exists to solve. Redis, Kafka, and
WebSockets are here to make the product real, but they are deliberately kept
**out of the correctness path** - PostgreSQL alone decides who owns a seat, and
a unique index makes double-selling structurally impossible.

Read [`docs/CONCURRENCY.md`](docs/CONCURRENCY.md) for the full argument.

## Stack

Java 21 - Spring Boot 3 - PostgreSQL 16 - Redis 7 - Kafka - Flyway - React 19 +
Vite + TypeScript + Tailwind - Docker - Testcontainers - k6

## Status

**Phase 0 of 10 complete.** Architecture, schema, and concurrency design are
documented. No code yet. See [`docs/STATE.md`](docs/STATE.md) for exactly where
things stand and what happens next.

## Documentation

| Document | What it answers |
| --- | --- |
| [AGENTS.md](AGENTS.md) | Stack, layout, and the rules that must not be broken |
| [docs/CONCURRENCY.md](docs/CONCURRENCY.md) | How double booking is prevented |
| [docs/SCHEMA.md](docs/SCHEMA.md) | Tables, constraints, and what each guarantees |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Module boundaries and request flow |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Why it is built this way |
| [docs/VERIFY.md](docs/VERIFY.md) | How to prove a change works |
| [docs/STATE.md](docs/STATE.md) | Where the last session stopped |
