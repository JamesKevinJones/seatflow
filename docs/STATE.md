# Project State

> Updated at the end of every session, by whichever agent was driving.
> Keep it under a page. This is a baton, not a diary.

**Last updated:** 2026-08-23 by claude-code

## Where things stand

**Phase 1 complete and verified.** The backend runs, migrates, and authenticates.

A user can register, log in, receive a JWT access token and an opaque refresh
token, call an authenticated endpoint, rotate the refresh token, and be locked
out of the whole token family if a used token is replayed. Role-gated endpoints
reject non-admins. Every error is RFC 9457 `application/problem+json`.

Proof, all actually run:

- `./mvnw clean compile` - BUILD SUCCESS
- Integration tests **in WSL**: 3 tests, 0 failures (Testcontainers, real PostgreSQL 16)
- `scripts/verify-auth.sh` against the running app: **15 passed, 0 failed**
- Flyway applied V1; Hibernate `ddl-auto=validate` accepted the mappings

Stack settled on **Spring Boot 4.1.1** (Framework 7, Security 7, Jackson 3).
Initializr no longer serves 3.x - see DECISIONS.

## In progress

Nothing half-done. Phase 1 finished at a clean boundary.

## The exact next step

Phase 2, awaiting Kevin's go-ahead: venues, sections, seats, events, and
EventSeat generation.

1. `V2__venues_sections_seats.sql` - venues, venue_sections, seats, with
   `UNIQUE (venue_section_id, row_label, seat_number)`.
2. `V3__events_event_seats.sql` - events plus `event_seats` exactly as specified
   in SCHEMA.md, including `ck_event_seat_state` and the partial index
   `ix_event_seats_expiring`. Leave `held_by_reservation_id` and `booking_id` as
   bare UUID columns; their FKs arrive in V4 and V5.
3. Domain entities and admin CRUD for venue and event.
4. EventSeat generation: creating an event materializes one `event_seats` row per
   seat in the venue. Must be idempotent - `uq_event_seat` is what enforces that.
5. Public read APIs: `GET /events`, `GET /events/{id}`, `GET /events/{id}/seats`.

Do **not** start the reservation engine in Phase 2. The atomic conditional UPDATE
lands in Phase 3, where it gets the concurrency test it deserves.

## Open questions

- **Reservation hold duration.** Assumed 10 minutes, configurable per event via
  `events.reservation_hold_seconds`. Not yet confirmed.
- **Seat map coordinates.** `seats` will carry x/y for rendering. Whether admins
  get a visual layout editor or CSV import is undecided; Phase 2 can ship CSV and
  defer the editor.
- **PENDING reservation status** was dropped to four states (see DECISIONS).
  Kevin approved the proposal without objecting, but did not answer directly.
- **CORS** is not configured yet. Phase 4 needs it for the Vite dev server on
  5173; deliberately deferred so it can be tested when there is a frontend.
- **Admin bootstrap.** No way to create an ADMIN user yet - registration always
  grants USER. Phase 2 needs either a seed migration or a CLI flag.

## Known traps

Ordered by how much time they cost.

- **WSL terminates seconds after the last command exits**, taking PostgreSQL and
  Redis with it. Always start `wsl -e bash -lc "sleep infinity" &` first. A
  "Connection refused" from the app almost always means this, not a config
  problem. Check `wsl -l -v` for STATE=Running before debugging anything else.
- **`./mvnw test` fails from Windows** at Docker discovery, by design. Docker
  Desktop is disabled; run integration tests inside WSL (VERIFY.md part 3).
- **The WSL port relay is IPv4-only** and `localhost` resolves to `::1` first.
  Configs name `127.0.0.1`. Do not "tidy" that back to `localhost`.
- **Boot 4 is not Boot 3.** Starters were renamed (`-webmvc`, not `-web`) and
  Jackson is now `tools.jackson.databind`. Blog snippets will not compile.
- **`NimbusJwtEncoder` needs an explicit HS256 header.** Without
  `JwsHeader.with(MacAlgorithm.HS256)` it defaults to RS256 and fails to select
  the symmetric key at runtime, not at startup.
- **A shell opened before `JAVA_HOME` was set** still holds the old JetBrains
  Runtime path. Re-export it in that shell.
- **Never test concurrency on H2**, and never mark a concurrency test
  `@Transactional`.
- **Do not remove the `status` predicate** from the seat-hold UPDATE in Phase 3.
  It is the entire double-booking defence.
