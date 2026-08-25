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

Multi-instance fan-out uses Redis pub/sub, added only when there is more than one
instance. Single instance until then.

---

## Redis, deliberately non-authoritative

Redis holds: the event and seat-map read cache, a short-TTL mirror of active
holds, rate-limiting counters, and WebSocket fan-out. It holds **no lock that any
correctness property depends on**. Full failure analysis in the Concurrency doc,
part 6.

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

### Why domain events do not carry seat updates

The system pushes two different things outward, and they want opposite delivery
semantics. It is worth being explicit about, because the tempting simplification
is to use one mechanism for both.

| | Live seat updates | Domain events |
| --- | --- | --- |
| Carried by | STOMP over WebSocket | Kafka |
| Needs to reach | **every** subscriber watching that seat map | **one** handler, once |
| If it is lost | the client sees a sequence gap and refetches over REST | the outbox still holds it, and the relay retries |
| Durability | none, and none needed - the map is refetchable | at-least-once, backed by PostgreSQL |

A confirmation email must be sent once, which is what a Kafka consumer group
gives: every instance joins group `seatflow`, and the broker hands each message
to exactly one of them. A seat update is the opposite - it has to reach every
connected browser, so it must arrive at every instance holding a subscriber.

Today that distinction costs nothing, because there is only one instance. It is
the thing that has to be solved first to run a second one: the STOMP broker is
Spring's in-memory one, so a second instance would broadcast only to its own
clients. Noted in the README's Known limits rather than pretended away.

### Consumers

`BookingNotificationListener` and `SalesAnalyticsListener` run in-process. The
brief asks for event-driven architecture, not microservices, and splitting the
booking transaction across a network boundary would destroy the single-commit
guarantee the whole design rests on. The published contract is what would let
either consumer move out unchanged.

Delivery is at-least-once, so both check a `messageId` against
`ProcessedMessages` before acting.
