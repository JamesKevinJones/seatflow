# Project State

> Updated at the end of every session, by whichever agent was driving.
> Keep it under a page. This is a baton, not a diary.

**Last updated:** 2026-08-25 by claude-code

## Where things stand

**Phase 3 complete. The reservation engine works, and the guarantee it exists
for is proven under real contention.**

End to end today: browse published events, open one, see live availability
including how many seats other people are holding, open the seat map, pick up to
eight seats, sign in, hold them, watch a ten-minute countdown, and release them
early. If someone takes one of your seats between choosing and holding, the
whole hold is refused, the response names exactly which seat was lost, and the
interface drops that one while keeping the rest of the selection.

Proof, all actually run:

- `./mvnw verify` in WSL: **3 surefire + 9 failsafe, 0 failures**
- `ConcurrentReservationIT`: 200 threads on one seat, exactly one winner; 200
  across ten seats, exactly ten held and none held twice; overlapping multi-seat
  requests all-or-nothing; lapsed holds reclaimable with the sweeper disabled
- `scripts/verify-reservations.sh` **21 passed**, `verify-catalog.sh` **29
  passed**, `verify-auth.sh` **15 passed**
- Browser: hold two seats (map 146/16 to 144/18), countdown ticks, release
  restores 146/16; a seat stolen mid-selection produces "1 of 2 requested seats
  are no longer available" and is greyed out while the other stays selected

## In progress

Nothing half-done. Phase 3 finished at a clean boundary.

## The exact next step

Phases 5 to 8 are all open. In rough order of value:

- **Phase 7 (payment and booking)** is the biggest functional gap - the hold
  currently expires and nothing can be bought. `SeatAllocationPort` needs its
  third method, `confirm(reservationId, bookingId)`, plus `V5__bookings_payments.sql`
  with the `uq_booking_seat_once` index that makes double-selling structurally
  impossible, and the `booking_id` foreign key V3 deferred.
- **Phase 6 (WebSockets)** would remove the need to refresh the seat map by
  hand. Broadcast only from `@TransactionalEventListener(AFTER_COMMIT)`.
- **Phase 9 (load testing)** would put real numbers behind the concurrency work,
  which is what a reader will want to see.
- **Phase 5 (Redis)** is the least urgent: nothing is slow yet, and the caching
  story is only interesting once there is traffic to cache.

## Open questions

- **Nothing can be bought.** Holds expire and the seats return. That is correct
  behaviour for Phase 3 but it is not a product yet.
- **CORS is still not configured**; the Vite dev proxy hides it.
- **Deployment.** Vercel suits the frontend but cannot host Spring Boot - the
  backend needs a container host plus managed Postgres and Redis. Free tiers
  sleep, which makes a shared link cold-start or fail.
- **`scripts/seed-demo.sh` still writes BOOKED seats directly**, because
  bookings do not exist. Replace that part when Phase 7 lands.
- **Event detail shows no price** - `EventResponse` carries availability but no
  price range.
- **Token storage** is localStorage; an httpOnly refresh cookie needs a backend
  change.

## Known traps

Ordered by how much time they cost.

- **WSL terminates seconds after the last command exits**, taking PostgreSQL and
  Redis with it. Start `wsl -e bash -lc "sleep infinity" &` first. "Connection
  refused" almost always means this.
- **CSS transitions freeze when the Browser pane is hidden**, so
  `getBoundingClientRect` returns mid-transition values. Inject
  `*{transition:none !important}` before measuring layout.
- **Sizing a concurrency-test thread pool below the task count deadlocks the
  build.** Running workers block on the start gate, queued tasks never reach
  `ready.countDown()`, and `ExecutorService.close()` waits forever. One thread
  per caller.
- **`mvn test` silently skips every `*IT`** and still prints BUILD SUCCESS. Use
  `mvn verify`. Expected: 3 surefire, 9 failsafe.
- **`./mvnw verify` fails from Windows** at Docker discovery, by design.
- **Fixtures that write `held_by_reservation_id` directly create orphans** that
  V4's foreign key then rejects at migration time. V4 cleans them up; do not
  reintroduce the pattern. Use the real reservation API.
- **`npm --prefix <path> run dev`**, not `npm run dev --prefix <path>`.
- **`.claude/launch.json` must use the 8.3 short path**; that is also why
  `server.fs.strict` is off.
- **The WSL port relay is IPv4-only**; configs name `127.0.0.1` deliberately.
- **A catch-all `@ExceptionHandler(Exception)` swallows security exceptions.**
- **Boot 4 is not Boot 3**: `-webmvc` not `-web`, Jackson is `tools.jackson.databind`.
- **`NimbusJwtEncoder` needs an explicit HS256 header.**
- **Never test concurrency on H2**, and never mark a concurrency test
  `@Transactional`.
- **Do not remove the `status` predicate** from `EventSeatRepository.tryHold`.
  It is the entire double-booking defence, and without it the query still looks
  correct.
