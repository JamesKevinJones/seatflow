# Project State

> Updated at the end of every session, by whichever agent was driving.
> Keep it under a page. This is a baton, not a diary.

**Last updated:** 2026-08-23 by claude-code

## Where things stand

Phase 0 complete. This repo currently contains **documentation and no code**. The
architecture, database schema, state machines, and concurrency strategy are
designed and written down; nothing has been built or compiled yet.

A user can do nothing with the project today. That is expected at this stage.

Toolchain confirmed working: Temurin 21.0.12.1 LTS installed, Node 24.18.1,
Docker 29.7.2 with Compose v5.5.0 inside WSL Ubuntu. Maven is deliberately not
installed - the Maven Wrapper will arrive with the Initializr-generated backend.

## In progress

Nothing half-done. Phase 0 finished cleanly at a documentation boundary.

## The exact next step

Phase 1, awaiting Kevin's go-ahead. In order:

1. Fix `JAVA_HOME` - it still points at `C:\Users\kj638\.jdks\jbr-21.0.11`
   (IntelliJ's JetBrains Runtime), not the newly installed Temurin. See VERIFY.md
   step 0. **Do this first or every build silently uses the wrong JDK.**
2. Generate `backend/` from `https://start.spring.io/starter.zip` with: web,
   data-jpa, security, oauth2-resource-server, validation, flyway, postgresql,
   data-redis, websocket, actuator, lombok, testcontainers.
3. Write `infra/docker-compose.dev.yml` with PostgreSQL 16 and Redis 7 only.
   No Kafka until Phase 8.
4. Write `V1__users_roles_auth.sql`: users, roles, user_roles, refresh_tokens.
5. Implement the `user` module and JWT auth, then verify with VERIFY.md steps 1,
   2, and 4.

## Open questions

- **Reservation hold duration.** Assumed 10 minutes, configurable per event via
  `events.reservation_hold_seconds`. Not yet confirmed with Kevin.
- **Seat map coordinates.** `seats` carries x/y for rendering. Whether the admin
  UI gets a visual layout editor or CSV import is undecided; Phase 2 can ship
  with CSV and defer the editor.
- **PENDING reservation status** was dropped to four states (see DECISIONS).
  Kevin approved the overall proposal without objecting, but did not answer the
  question directly. Reversible if he wants it back.

## Known traps

- **`JAVA_HOME` points at the wrong JDK.** JetBrains Runtime, not Temurin. Fix
  before the first build.
- **Windows `docker` CLI is broken by design.** It targets the disabled Docker
  Desktop pipe. Every docker command must run inside WSL. `docker ps` from
  Windows failing is not a problem to debug.
- **The project path contains a space** (`Kevin codes`), which breaks
  `preview_start`. Use the 8.3 short path in `.claude/launch.json` from Phase 4.
- **Never test concurrency on H2.** It does not reproduce PostgreSQL row-lock
  semantics; the test would pass and prove nothing. Testcontainers only.
- **Do not remove the `status` predicate** from the seat-hold UPDATE, however
  redundant it looks. It is the entire double-booking defence.
