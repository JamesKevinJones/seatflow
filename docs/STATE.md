# Project State

> Updated at the end of every session, by whichever agent was driving.
> Keep it under a page. This is a baton, not a diary.

**Last updated:** 2026-08-24 by claude-code

## Where things stand

**Phase 4 complete, apart from the hold itself. Phase 3 was skipped and is now
the gap in the middle of the product.**

End to end today: browse published events, open one, see real availability
including how many seats other people are holding, open the seat map, pick up to
eight seats with a running total, register or sign in, and press "Hold these
seats" - which fails with a plain message, because `POST /api/v1/reservations`
does not exist yet.

Proof, all actually run:

- `npm run build` - clean (`tsc -b` + vite, 90 kB gzipped JS)
- Backend `./mvnw verify` in WSL: 3 surefire + 5 failsafe, 0 failures
- `scripts/verify-auth.sh` 15 passed, `scripts/verify-catalog.sh` 29 passed
- Browser: 188 seats render with counts matching the database exactly
  (143 available / 19 held / 26 sold), sold seats disabled, selection cap works,
  sign-in flow works, deep links work, no console errors, no horizontal overflow
  at 375px or 1330px

## In progress

Nothing half-done. Phase 4 finished at a clean boundary.

## The exact next step

**Phase 3, the reservation engine.** It is now the only thing between this and a
working product, and it is the phase the project exists for.

Read `docs/CONCURRENCY.md` first - the design is already argued out.

1. `V4__reservations.sql` - `reservations`, `reservation_seats`, and the
   `ALTER TABLE event_seats ADD CONSTRAINT fk_event_seats_held_by` that V3 left out.
2. `SeatAllocationPort` in the `event` module (`tryHold` / `release` / `confirm`).
3. The atomic conditional UPDATE, per CONCURRENCY.md Layer 1. Native query,
   `@Modifying(clearAutomatically = true, flushAutomatically = true)`, `status`
   in the WHERE clause, seat ids sorted before binding.
4. `ReservationService`: one transaction, compare affected rows to requested
   count, throw and roll back everything if they differ.
5. `POST /api/v1/reservations` accepting `{eventId, seatIds[]}` and the
   `Idempotency-Key` header - **the frontend already sends exactly this**. A 409
   must carry `unavailableSeatIds`; the seat tray already reads that field.
6. Expiry: lazy predicate for correctness, `@Scheduled` sweeper under
   `pg_try_advisory_lock` for UX.
7. **`ConcurrentReservationIT`** - 200 threads, one winner, 199 failures, exactly
   one RESERVED row. Not `@Transactional`. Named `*IT` so failsafe runs it.

When it lands, delete the 404-specific copy in `SeatSelection.tsx` and
`scripts/seed-demo.sh` (which fakes held/sold states by writing them directly).

## Open questions

- **CORS is still not configured**, and the Vite dev proxy hides that. Any real
  deployment with frontend and backend on different origins needs it.
- **Deployment.** Vercel suits the frontend but cannot host Spring Boot; the
  backend needs a container host plus managed Postgres and Redis. Deferred to
  Phase 10, and pointless before Phase 3 exists.
- **Event detail shows no price.** `EventResponse` carries availability but no
  price range. Either add one to the DTO or leave price to the seat map.
- **Seat map payload.** 188 seats is fine; a 2,000 seat venue returns 2,000
  objects per page view. Phase 5 caching, not pagination.
- **PENDING reservation status** was dropped to four states (see DECISIONS).
  Phase 3 is where that becomes concrete.
- **Token storage** is localStorage. The right answer is an httpOnly cookie for
  the refresh token, which needs a backend change.

## Known traps

Ordered by how much time they cost.

- **WSL terminates seconds after the last command exits**, taking PostgreSQL and
  Redis with it. Start `wsl -e bash -lc "sleep infinity" &` first. "Connection
  refused" almost always means this - check `wsl -l -v` before debugging.
- **CSS transitions freeze when the Browser pane is not displayed**, so
  `getBoundingClientRect` returns mid-transition values. Inject
  `*{transition:none !important}` before measuring layout, or you will "fix" a
  bug that is not there. This cost real time in Phase 4.
- **`mvn test` silently skips every `*IT`** and still prints BUILD SUCCESS. Use
  `mvn verify`. Expected: 3 surefire, 5 failsafe.
- **`./mvnw verify` fails from Windows** at Docker discovery, by design. Run it
  inside WSL.
- **`npm --prefix <path> run dev`**, not `npm run dev --prefix <path>` - the
  latter passes the flag to Vite.
- **`.claude/launch.json` must use the 8.3 short path**; the launcher cannot pass
  a path with a space. That is also why `server.fs.strict` is off.
- **The dev server restarts when `vite.config.ts` changes**, so a reload issued
  at that moment lands on a browser error page. Not an app bug.
- **The WSL port relay is IPv4-only** and `localhost` resolves to `::1` first.
  Configs name `127.0.0.1`. Do not "tidy" that back.
- **A catch-all `@ExceptionHandler(Exception)` swallows security exceptions.**
  Keep the explicit `AccessDeniedException` handler ahead of it.
- **Boot 4 is not Boot 3**: `-webmvc` not `-web`, and Jackson is
  `tools.jackson.databind`.
- **`NimbusJwtEncoder` needs an explicit HS256 header**, or it defaults to RS256
  and fails at runtime.
- **Never test concurrency on H2**, and never mark a concurrency test
  `@Transactional`.
- **Do not remove the `status` predicate** from the seat-hold UPDATE in Phase 3.
  It is the entire double-booking defence.
