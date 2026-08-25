# Database Schema

PostgreSQL 16. All changes ship as Flyway migrations. Hibernate `ddl-auto` is
`validate` and must never be `update`.

Two conventions apply everywhere:

- **Money is `BIGINT` cents** mapped to Java `long`. Never floating point. This is
  what real payment systems do and it removes all rounding discussion.
- **Primary keys are `UUID`**, generated application-side by Hibernate's
  `@UuidGenerator`. Trade-off noted in DECISIONS: 16 bytes and poor insert
  locality versus a time-ordered generator, which we can swap in if index bloat
  ever shows up in profiling.

---

## Table inventory

```
users            roles            user_roles        refresh_tokens*
venues           venue_sections   seats
events           event_seats
reservations     reservation_seats
bookings         booking_seats
payments                                            outbox*
```

`*` = beyond the original brief. `refresh_tokens` supports token rotation with
reuse detection; `outbox` carries domain events to Kafka without a dual write.

---

## Relationships

```
venues --1:N-- venue_sections --1:N-- seats
   |                                    |
   | 1:N                                | 1:N
   v                                    v
events ----------1:N-----------> event_seats  <-- the contended row
                                    |    ^
                     N:1 (held_by)  |    | N:M via reservation_seats
                                    v    |
users --1:N--> reservations --------+----+
                    |
                    | 1:1                   1:1
                    +------> bookings ----------> booking_seats --> event_seats
                    |                                  (UNIQUE)
                    +------> payments
```

The key modelling decision: a **Seat** is a permanent physical position in a
venue, while an **EventSeat** is that seat's saleable state for one specific
event. Row 4, seat 12 in the Grand Hall exists once as a `seats` row and once per
event as an `event_seats` row. All contention happens on `event_seats`.

---

## The three tables that carry the invariant

### event_seats

One row per (event, seat). Every reservation and booking arbitration happens here.

```sql
CREATE TABLE event_seats (
    id                     UUID PRIMARY KEY,
    event_id               UUID NOT NULL REFERENCES events(id),
    seat_id                UUID NOT NULL REFERENCES seats(id),
    status                 TEXT NOT NULL,          -- AVAILABLE | RESERVED | BOOKED
    price_cents            BIGINT NOT NULL CHECK (price_cents >= 0),
    held_by_reservation_id UUID,                   -- FK added in V4
    held_until             TIMESTAMPTZ,
    booking_id             UUID,                   -- FK added in V5
    version                BIGINT NOT NULL DEFAULT 0,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_event_seat UNIQUE (event_id, seat_id),
    CONSTRAINT ck_event_seat_state CHECK (
        (status = 'AVAILABLE' AND held_by_reservation_id IS NULL AND held_until IS NULL)
     OR (status = 'RESERVED'  AND held_by_reservation_id IS NOT NULL AND held_until IS NOT NULL)
     OR (status = 'BOOKED'    AND booking_id IS NOT NULL))
);

CREATE INDEX ix_event_seats_event_status ON event_seats (event_id, status);
CREATE INDEX ix_event_seats_expiring     ON event_seats (held_until)
                                          WHERE status = 'RESERVED';
```

What each constraint buys:

- `uq_event_seat` makes EventSeat generation idempotent. Re-running seat
  generation for an event cannot produce duplicate rows.
- `ck_event_seat_state` makes an incoherent row unrepresentable. A RESERVED seat
  with no holder, or an AVAILABLE seat that still carries a hold expiry, is
  rejected by the database rather than debugged later.
- `ix_event_seats_expiring` is partial, so the sweeper scans only live holds
  rather than the whole table.

### booking_seats

The structural backstop. See the Concurrency doc, Layer 2.

```sql
CREATE TABLE booking_seats (
    booking_id    UUID NOT NULL REFERENCES bookings(id),
    event_seat_id UUID NOT NULL REFERENCES event_seats(id),
    price_cents   BIGINT NOT NULL,
    PRIMARY KEY (booking_id, event_seat_id)
);

CREATE UNIQUE INDEX uq_booking_seat_once ON booking_seats (event_seat_id);
```

That one index is the hard guarantee. A seat can be sold at most once, ever,
regardless of what the application layer does.

### reservations and payments

```sql
CREATE TABLE reservations (
    id              UUID PRIMARY KEY,
    event_id        UUID NOT NULL REFERENCES events(id),
    user_id         UUID NOT NULL REFERENCES users(id),
    status          TEXT NOT NULL,   -- ACTIVE | EXPIRED | CANCELLED | COMPLETED
    expires_at      TIMESTAMPTZ NOT NULL,
    idempotency_key TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reservation_idem UNIQUE (user_id, idempotency_key)
);

-- At most one live payment per reservation.
CREATE UNIQUE INDEX uq_payment_inflight ON payments (reservation_id)
    WHERE status IN ('PROCESSING', 'SUCCESS');
```

---

## Forward-referencing foreign keys

`event_seats` is created in V3 but references `reservations` (V4) and `bookings`
(V5). Those two columns therefore ship as bare `UUID` in V3, and the later
migration adds the constraint:

```sql
-- in V4__reservations.sql, after reservations exists
ALTER TABLE event_seats
    ADD CONSTRAINT fk_event_seats_held_by
    FOREIGN KEY (held_by_reservation_id) REFERENCES reservations(id);
```

This is deliberate, not an oversight. Do not try to "fix" it by reordering the
migrations.

There is no FK cycle: `event_seats` points at `reservations`, `reservations`
points at `events`, and nothing points back at `event_seats` except
`reservation_seats` and `booking_seats`, which nothing else references.

---

## Why event_seats carries held_by_reservation_id

It is a denormalization. The holder could be derived by joining
`reservation_seats`, but keeping it on the row means:

- The atomic hold UPDATE stamps ownership in the same statement that wins the
  seat, with no second write.
- The expiry sweeper is a single UPDATE with no join.

`reservation_seats` is still kept, because it is the historical record of what a
reservation contained and it holds the price snapshot at hold time. That survives
after the seat is released and re-reserved by someone else.

---

## outbox

The one table that is not about tickets. It exists so that publishing a domain
event and committing the change it describes are the same commit - the full
argument is in the Concurrency doc, part 8.

| Column | Why it is there |
| --- | --- |
| `id BIGSERIAL` | Insertion order. The relay reads oldest first, and a monotonic key is what makes that meaningful. |
| `message_id UUID UNIQUE` | Identity of one emission. Consumers use it to discard the duplicates that at-least-once delivery implies; the unique constraint catches a producer that records the same thing twice. |
| `message_type`, `aggregate_type`, `aggregate_id` | For humans reading the table. The relay branches on none of them. |
| `topic`, `partition_key` | Routing, resolved once at record time, so the relay needs no domain knowledge at all. |
| `payload TEXT` | Already serialized. **Not `JSONB`** - the relay publishes the exact bytes that were committed, and JSONB normalises the value by reordering keys and dropping whitespace. The payload's schema is the consumers' concern, not PostgreSQL's. |
| `published_at` | NULL means "still owed". This is the entire queue state. |
| `attempts`, `last_error` | Evidence. A row with rising attempts is a message the broker keeps refusing. |

Two indexes, both partial:

```sql
CREATE INDEX ix_outbox_unpublished ON outbox (id)           WHERE published_at IS NULL;
CREATE INDEX ix_outbox_published   ON outbox (published_at) WHERE published_at IS NOT NULL;
```

The first is the relay's only read path and stays small, because it indexes the
backlog rather than the history. It is also the reason there is no cursor
anywhere: sequence values are assigned at INSERT but rows appear at COMMIT, so a
"last id processed" watermark can step over a row that was numbered earlier and
committed later. "What is still unpublished" cannot have that bug.

The second exists only for the retention sweep, which deletes published rows
after a week.

`outbox` has no foreign keys, deliberately. A message is a statement about
something that happened, and it has to stay readable and sendable even if the
booking it describes is later deleted.

---

## Migration plan

```
V1__users_roles_auth.sql          Phase 1   users, roles, user_roles, refresh_tokens
V2__venues_sections_seats.sql     Phase 2   static venue inventory
V3__events_event_seats.sql        Phase 2   events + generated event_seats
V4__reservations.sql              Phase 3   reservations, reservation_seats, FK on event_seats
V5__bookings_payments.sql         Phase 7   bookings, booking_seats, payments
V6__outbox.sql                    Phase 8   transactional outbox for Kafka
```
