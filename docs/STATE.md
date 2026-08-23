# Project State

> Updated at the end of every session, by whichever agent was driving.
> Keep it under a page. This is a baton, not a diary.

**Last updated:** 2026-08-23 by claude-code

## Where things stand

**Phase 2 complete and verified.** There is now a real catalogue.

An admin can create a venue with a full seat layout, create an event at that
venue, have one `event_seats` row generated per physical seat with per-section
pricing, publish it, and cancel it. Anyone can browse published events and load
an interactive seat map without an account. Draft events return 404 to the
public, not 403.

Proof, all actually run:

- `./mvnw verify` **in WSL**: 3 surefire + 5 failsafe tests, 0 failures
- `scripts/verify-auth.sh`: **15 passed, 0 failed**
- `scripts/verify-catalog.sh`: **29 passed, 0 failed**
- Flyway at v3; all `event_seats` invariant constraints verified to reject bad data

## In progress

Nothing half-done. Phase 2 finished at a clean boundary.

## The exact next step

**Phase 3, the reservation engine. This is the phase the project exists for.**

Read `docs/CONCURRENCY.md` first - it is the design, already argued out.

1. `V4__reservations.sql` - `reservations`, `reservation_seats`, and the
   `ALTER TABLE event_seats ADD CONSTRAINT fk_event_seats_held_by` that V3
   deliberately left out.
2. `SeatAllocationPort` in the `event` module, exposing `tryHold` / `release` /
   `confirm`. Define it now, with a consumer - not before.
3. The atomic conditional UPDATE, as specified in CONCURRENCY.md Layer 1.
   `@Modifying(clearAutomatically = true, flushAutomatically = true)`, native
   query, `status` in the WHERE clause, seat ids sorted before binding.
4. `ReservationService` - one transaction, compare affected rows against
   requested count, throw and roll back everything if they differ.
5. `POST /api/v1/reservations` with `Idempotency-Key`, and a 409 that names the
   lost seats in `unavailableSeatIds`.
6. Expiry: the lazy predicate carries correctness; a `@Scheduled` sweeper under
   `pg_try_advisory_lock` carries UX.
7. **`ConcurrentReservationIT`** - 200 threads on a `CountDownLatch`, exactly 1
   winner, 199 `SeatsUnavailableException`, exactly 1 RESERVED row. Not
   `@Transactional`. Must be named `*IT` and run under `mvn verify`.

## Open questions

- **Reservation hold duration.** Implemented as `events.reservation_hold_seconds`,
  default 600. Confirm 10 minutes is the intended default.
- **Seat map coordinates.** `seats.position_x/y` are populated by a naive grid
  during row generation. Good enough to render; a real layout editor is deferred.
- **PENDING reservation status** was dropped to four states (see DECISIONS).
  Kevin approved the proposal without objecting, but did not answer directly.
  Phase 3 is where this becomes concrete.
- **CORS** still not configured. Phase 4 needs it for Vite on 5173.
- **Seat map payload size.** A 2,000 seat venue returns 2,000 objects on every
  page view. Fine now; Phase 5 caching is the answer, not pagination.

## Known traps

Ordered by how much time they cost.

- **WSL terminates seconds after the last command exits**, taking PostgreSQL and
  Redis with it. Always start `wsl -e bash -lc "sleep infinity" &` first. A
  "Connection refused" from the app almost always means this. Check
  `wsl -l -v` for STATE=Running before debugging anything else.
- **`mvn test` silently skips every `*IT` class** and still prints BUILD SUCCESS.
  Use `mvn verify`. Expected: 3 surefire, 5 failsafe.
- **`./mvnw verify` fails from Windows** at Docker discovery, by design. Docker
  Desktop is disabled; run it inside WSL.
- **The WSL port relay is IPv4-only** and `localhost` resolves to `::1` first.
  Configs name `127.0.0.1`. Do not "tidy" that back to `localhost`.
- **A catch-all `@ExceptionHandler(Exception)` swallows security exceptions.**
  Method-security denials are thrown inside the controller invocation, so they
  reach the advice before the security filter chain. Any new catch-all must keep
  the explicit `AccessDeniedException` handler ahead of it.
- **Boot 4 is not Boot 3.** Starters renamed (`-webmvc`, not `-web`); Jackson is
  `tools.jackson.databind`. Blog snippets will not compile.
- **`NimbusJwtEncoder` needs an explicit HS256 header**, or it defaults to RS256
  and fails to select the symmetric key at runtime, not at startup.
- **A shell opened before `JAVA_HOME` was set** still holds the old JetBrains
  Runtime path. Re-export it in that shell.
- **Never test concurrency on H2**, and never mark a concurrency test
  `@Transactional`.
- **Do not remove the `status` predicate** from the seat-hold UPDATE in Phase 3.
  It is the entire double-booking defence.
