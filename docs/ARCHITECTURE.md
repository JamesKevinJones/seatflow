# Architecture

A **modular monolith**. Not microservices, and not because microservices are hard
- because splitting the booking transaction across a network boundary would
destroy the single-transaction guarantee that the whole project rests on. Kafka
carries asynchronous side effects, never the booking itself.

---

## Module boundaries

```
com.seatflow
  common/        config, RFC 9457 error handling, security, shared utils
  user/          accounts, roles, JWT issuance and refresh
  venue/         venues, sections, physical seats (static inventory)
  event/         events, event_seats, owns SeatAllocationPort
  reservation/   the concurrency engine
  booking/       confirmed bookings
  payment/       simulated payment
  notification/  WebSocket broadcasts
  messaging/     transactional outbox, Kafka relay, published event contract
```

Each module has `domain / application / infrastructure / presentation`.

**The one real seam.** `reservation` must mutate `event_seats`, which `event`
owns. Rather than reaching into another module's repository, `event` exposes a
narrow port:

```java
public interface SeatAllocationPort {
    int tryHold(UUID eventId, List<UUID> seatIds, UUID reservationId, Instant until);
    int release(UUID reservationId);
    int confirm(UUID reservationId, UUID bookingId);
}
```

`reservation` depends on that interface only. Naming this boundary now is what
stops the two modules fusing into a single ball of mud by Phase 7.

---

## Request flow: reserve seats

```
POST /api/v1/reservations  { eventId, seatIds[] }   + Idempotency-Key
        |
        v
  JWT filter (Spring Security OAuth2 resource server)
        |
        v
  ReservationController  -- validates DTO, no business logic
        |
        v
  ReservationService  @Transactional
        |
        +--> idempotency check: existing reservation for this key? return it
        +--> insert reservations row (status ACTIVE)
        +--> SeatAllocationPort.tryHold(...)   <-- ONE atomic conditional UPDATE
        +--> claimed != requested?  throw -> ROLLBACK EVERYTHING
        +--> insert reservation_seats rows
        |
        v
  COMMIT
        |
        v
  @TransactionalEventListener(AFTER_COMMIT)
        |
        +--> WebSocket broadcast to /topic/events/{eventId}/seats
        +--> Redis cache invalidation
        +--> Kafka publish (Phase 8, via outbox)
```

**After-commit is not a detail.** Broadcasting inside the transaction would show
every connected client a seat as RESERVED that may still roll back. This is a real
bug in most tutorial implementations.

---

## Authentication

Spring Security's **OAuth2 Resource Server** with `JwtEncoder` / `JwtDecoder`
beans, not `jjwt` plus a hand-rolled `OncePerRequestFilter`.

The filter approach is what most tutorials show; the resource-server approach is
what Spring Boot 3 actually intends, and it means far less hand-written security
code to get wrong. Access tokens are short-lived JWTs. Refresh tokens are opaque,
stored hashed in `refresh_tokens`, and rotated on every use with reuse detection.

---

## Error model

**RFC 9457 Problem Details** via Spring Boot 3's native `ProblemDetail`, not a
bespoke `ApiResponse<T>` wrapper. A seat conflict returns 409:

```json
{
  "type": "https://seatflow.dev/problems/seat-unavailable",
  "title": "Seat unavailable",
  "status": 409,
  "detail": "2 of 3 requested seats are no longer available.",
  "eventId": "...",
  "unavailableSeatIds": ["...", "..."]
}
```

Telling the client which seats it lost lets the seat map re-render rather than
discarding the whole selection.

Status codes: 200, 201, 400 (validation), 401, 403, 404, 409 (seat conflict or
duplicate payment), 422 (expired reservation).

---

## Real-time updates

STOMP over WebSocket. Topic per event:

```
/topic/events/{eventId}/seats
```

Payload is a delta, not a snapshot:

```json
{ "seatIds": ["..."], "status": "RESERVED", "seq": 4213, "at": "..." }
```

Two rules that keep the client honest:

1. Every delta carries a per-event monotonic `seq`. A client that sees a gap knows
   it missed a message.
2. On connect, reconnect, or detected gap, the client **re-fetches the full seat
   map over REST**. Deltas are an optimization; REST is the truth.

Without these the seat map silently drifts out of sync and nobody notices until a
user clicks a seat that is already gone.

The `seq` is issued by Redis `INCR`, so it is one sequence per event across the
whole cluster rather than per instance, and every instance receives every update
over the Redis fan-out channel. See "Making a second instance possible" below.

---

## Redis, deliberately non-authoritative

Redis holds the event and seat-map read cache, the per-event broadcast sequence
counter, and the seat-update fan-out channel. It holds **no lock, and no data,
that any correctness property depends on**.

The last two arrived with multi-instance support and are worth being precise
about, because they are not cache. Neither is authoritative:

- **Sequence counter.** If Redis is unreachable each instance falls back to a
  local counter. Clients then see gaps, and a gap already means "refetch the map
  over REST". An outage costs more refetching, never a wrong map.
- **Fan-out channel.** If the publish fails, the update is delivered to this
  instance's own subscribers directly. Other instances' clients miss it and
  recover through the same gap mechanism.

Killing Redis still leaves reservations, expiry, payment and booking working.
Measured with the container stopped: reservations returned 201, six seats were
held correctly, and nothing was sold twice - but each reservation took **3.05s
against 42ms healthy**, and a seat map read 1.02s against 100ms. The
after-commit path makes three separate Redis calls and each waits out the
timeout; the seat map makes one.

That is the honest cost, and it is the residue of a timeout choice rather than a
design one. Removing it means moving the after-commit block off the request
thread entirely, which is noted rather than done. Full failure analysis in the
Concurrency doc, part 6.

---

## Kafka, through a transactional outbox

Topics, all partitioned by the show so one event's history stays ordered:
`booking.confirmed`, `reservation.expired`, `payment.completed`.

Nothing publishes to Kafka from inside a business transaction. A module records
a `DomainEvent`, which is an insert into the `outbox` table joining whatever
transaction the caller is already in; a relay moves rows to the broker
afterwards. The reasoning, including why the alternative has no safe ordering,
is in the Concurrency doc, part 8.

```
PaymentLedger.settle()        [one transaction]
  booking + booking_seats
  event_seats -> BOOKED
  payment -> SUCCESS
  outbox: booking.confirmed
  outbox: payment.completed
  COMMIT  <- all of it, or none of it

OutboxRelay.drain()           [every 500ms, separate transaction]
  SELECT ... WHERE published_at IS NULL ORDER BY id
    FOR UPDATE SKIP LOCKED
  send to Kafka
  UPDATE ... SET published_at = now()
```

`messaging` depends on no other module. Modules depend on `OutboxRecorder` and
the records in `messaging.contract`, never the other way round, so the direction
of the dependency matches the direction of the data.

### Two fan-out mechanisms, on purpose

The system pushes two different things outward, and they want opposite delivery
semantics. Using one mechanism for both is the tempting simplification and is
wrong in either direction.

| | Live seat updates | Domain events |
| --- | --- | --- |
| Carried by | Redis pub/sub | Kafka |
| Delivered to | **every** instance, so every browser hears it | **one** instance, whichever holds the partition |
| Why | each instance holds its own WebSocket sessions | three instances must not send three confirmation emails |
| If it is lost | the client sees a sequence gap and refetches over REST | the outbox still holds it, and the relay retries |
| Durability | none, and none needed | at-least-once, backed by PostgreSQL |
| Latency | sub-millisecond push | poll interval plus relay tick |

Kafka consumer groups deliver a message to one member of the group, which is
exactly right for a confirmation email and exactly wrong for a seat map: the
instance that received it would update its own browsers and the others would
show a stale seat until someone reloaded. Redis pub/sub is the reverse.

### Making a second instance possible

Spring's simple STOMP broker only knows the sessions attached to its own JVM, so
before this the second instance was a silent bug rather than a loud one. Three
things had to change, none of which fails on a single node:

```
SeatStatusChanged (AFTER_COMMIT)
        |
        v
SeatUpdateSequence.next()      Redis INCR - one counter per event, cluster-wide
        |
        v
SeatUpdateFanout.publish()     -> Redis channel seatflow:seat-updates
        |
        +-- every instance, including this one --+
                                                 v
                              SeatUpdateSubscriber -> SeatUpdateDelivery
                                                        -> local STOMP sessions
```

The originating instance does **not** deliver locally and also publish; it only
publishes, and receives its own message back like everyone else. One code path
from "a seat changed" to "a browser was told", running identically wherever the
change happened.

The sequence counter moved from a local `AtomicLong` to Redis `INCR`. Two
instances with their own counters would send a browser 1, 1, 2, 2 - read as a gap
by the client, which then refetches the whole map on every update. Live updates
would appear to work while costing a full REST round trip each time.

Deliberately **not** a database column. The obvious version - bumping a
`seat_version` on the events row inside the reservation transaction - would put
every concurrent hold for one event behind a single row lock, which is a hotspot
introduced into the exact path the project exists to keep fast.

### What else a second instance needed

| Concern | How it is handled | Would have happened otherwise |
| --- | --- | --- |
| Expiry sweeper | `pg_try_advisory_xact_lock`, non-blocking | Every instance sweeping the same rows |
| Outbox relay | `FOR UPDATE SKIP LOCKED`, no leader | Instances blocking on each other, or sending duplicates |
| Domain event handling | one Kafka consumer group | One confirmation email per instance |
| Admin bootstrap | advisory lock taken **before** the read | All instances insert, all but one hit `uq_users_email_lower`, and an `ApplicationRunner` that throws stops the application |
| Load balancing | nginx re-resolves `backend` via Docker DNS | A static `upstream` resolves once at startup and pins every request to one replica |
| Metrics | every meter tagged with the instance | Two instances collapsing into one flickering series |

WebSocket connections need no stickiness. A socket stays on whichever instance
answered the handshake, and every instance receives every update - needing
sticky sessions here would mean the fan-out was broken.

### Consumers

`BookingNotificationListener` and `SalesAnalyticsListener` run in-process. The
brief asks for event-driven architecture, not microservices, and splitting the
booking transaction across a network boundary would destroy the single-commit
guarantee the whole design rests on. The published contract is what would let
either consumer move out unchanged.

Delivery is at-least-once, so both check a `messageId` against
`ProcessedMessages` before acting.
