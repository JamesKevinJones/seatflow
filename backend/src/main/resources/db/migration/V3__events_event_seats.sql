-- Phase 2: events, and the per-event saleable state of every seat.
--
-- event_seats is the contended row. Everything in docs/CONCURRENCY.md happens
-- here. Read that before changing this table.

CREATE TABLE events (
    id          UUID        PRIMARY KEY,
    venue_id    UUID        NOT NULL REFERENCES venues(id),
    name        TEXT        NOT NULL,
    slug        TEXT        NOT NULL,
    description TEXT,
    category    TEXT        NOT NULL,
    poster_url  TEXT,

    starts_at   TIMESTAMPTZ NOT NULL,
    ends_at     TIMESTAMPTZ NOT NULL,

    -- Sales window. Null means "no restriction on that end".
    sales_start_at TIMESTAMPTZ,
    sales_end_at   TIMESTAMPTZ,

    status      TEXT        NOT NULL DEFAULT 'DRAFT',

    -- How long a hold survives without payment, per event. High-demand events
    -- may want a shorter window than the 10 minute default.
    reservation_hold_seconds INTEGER NOT NULL DEFAULT 600,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_events_slug UNIQUE (slug),
    CONSTRAINT ck_events_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT ck_events_name_present CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_events_time_order CHECK (ends_at > starts_at),
    CONSTRAINT ck_events_sales_order CHECK (
        sales_start_at IS NULL OR sales_end_at IS NULL OR sales_end_at > sales_start_at),
    CONSTRAINT ck_events_hold_positive CHECK (reservation_hold_seconds > 0)
);

-- The event list is browsed by "what is on sale, soonest first".
CREATE INDEX ix_events_status_starts ON events (status, starts_at);
CREATE INDEX ix_events_venue ON events (venue_id);


CREATE TABLE event_seats (
    id                     UUID    PRIMARY KEY,
    event_id               UUID    NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    seat_id                UUID    NOT NULL REFERENCES seats(id),

    status                 TEXT    NOT NULL DEFAULT 'AVAILABLE',
    price_cents            BIGINT  NOT NULL,

    -- Deliberately bare UUIDs, not foreign keys, until the tables they point at
    -- exist. V4 adds the reservations FK, V5 adds the bookings FK. This is
    -- intentional; do not "fix" it by reordering the migrations.
    held_by_reservation_id UUID,
    held_until             TIMESTAMPTZ,
    booking_id             UUID,

    version                BIGINT      NOT NULL DEFAULT 0,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Makes EventSeat generation idempotent. Re-running generation for an event
    -- cannot produce a second row for the same physical seat.
    CONSTRAINT uq_event_seat UNIQUE (event_id, seat_id),

    CONSTRAINT ck_event_seat_status CHECK (status IN ('AVAILABLE', 'RESERVED', 'BOOKED')),
    CONSTRAINT ck_event_seat_price CHECK (price_cents >= 0),

    -- Makes an incoherent row unrepresentable. A RESERVED seat with no holder,
    -- or an AVAILABLE seat still carrying a hold expiry, is rejected by the
    -- database rather than discovered in production three weeks later.
    CONSTRAINT ck_event_seat_state CHECK (
        (status = 'AVAILABLE' AND held_by_reservation_id IS NULL AND held_until IS NULL)
     OR (status = 'RESERVED'  AND held_by_reservation_id IS NOT NULL AND held_until IS NOT NULL)
     OR (status = 'BOOKED'    AND booking_id IS NOT NULL))
);

-- Seat maps are read per event, and availability counts filter on status.
CREATE INDEX ix_event_seats_event_status ON event_seats (event_id, status);

-- Partial, so the expiry sweeper scans only live holds instead of every row in
-- the table. Most rows are AVAILABLE or BOOKED and are irrelevant to it.
CREATE INDEX ix_event_seats_expiring ON event_seats (held_until)
    WHERE status = 'RESERVED';
