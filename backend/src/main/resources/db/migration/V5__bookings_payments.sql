-- Phase 7: simulated payment, and the booking it produces.
--
-- The consistency argument is in docs/CONCURRENCY.md part 7. The single most
-- important line in this file is uq_booking_seat_once.

CREATE TABLE payments (
    id                 UUID        PRIMARY KEY,
    reservation_id     UUID        NOT NULL REFERENCES reservations(id),
    user_id            UUID        NOT NULL REFERENCES users(id),

    amount_cents       BIGINT      NOT NULL CHECK (amount_cents >= 0),
    currency           TEXT        NOT NULL DEFAULT 'INR',

    status             TEXT        NOT NULL DEFAULT 'PENDING',
    provider_reference TEXT,
    failure_reason     TEXT,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_payment_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED'))
);

-- At most one live payment per reservation, ever.
--
-- Partial on purpose: a FAILED payment leaves the index, so a customer whose
-- card was declined may try again. A PROCESSING or SUCCESS one does not, so a
-- double-submit cannot charge twice. This is the database enforcing what the
-- FOR UPDATE lock in PaymentService coordinates.
CREATE UNIQUE INDEX uq_payment_inflight ON payments (reservation_id)
    WHERE status IN ('PROCESSING', 'SUCCESS');

CREATE INDEX ix_payments_user ON payments (user_id, created_at DESC);


CREATE TABLE bookings (
    id                UUID        PRIMARY KEY,
    reservation_id    UUID        NOT NULL REFERENCES reservations(id),
    payment_id        UUID        NOT NULL REFERENCES payments(id),
    user_id           UUID        NOT NULL REFERENCES users(id),
    event_id          UUID        NOT NULL REFERENCES events(id),

    booking_reference TEXT        NOT NULL,
    total_cents       BIGINT      NOT NULL CHECK (total_cents >= 0),
    currency          TEXT        NOT NULL DEFAULT 'INR',
    status            TEXT        NOT NULL DEFAULT 'CONFIRMED',

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- One booking per reservation. A retried confirmation cannot produce two.
    CONSTRAINT uq_booking_reservation UNIQUE (reservation_id),
    CONSTRAINT uq_booking_reference UNIQUE (booking_reference),
    CONSTRAINT ck_booking_status CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);

CREATE INDEX ix_bookings_user ON bookings (user_id, created_at DESC);
CREATE INDEX ix_bookings_event ON bookings (event_id);


CREATE TABLE booking_seats (
    booking_id    UUID   NOT NULL REFERENCES bookings(id),
    event_seat_id UUID   NOT NULL REFERENCES event_seats(id),
    price_cents   BIGINT NOT NULL CHECK (price_cents >= 0),

    PRIMARY KEY (booking_id, event_seat_id)
);

-- The structural backstop, and the strongest guarantee in the system.
--
-- A booking_seats row exists only for a confirmed booking, so a seat can appear
-- in at most one booking for the rest of time. Every layer above this can have
-- a bug and the database will still refuse to sell the same seat twice. It is
-- the reason "a seat is never sold twice" can be stated without qualification.
CREATE UNIQUE INDEX uq_booking_seat_once ON booking_seats (event_seat_id);


-- Same story as V4's holder column: booking_id has been a bare UUID since V3,
-- so it may contain values referencing nothing - development fixtures marked
-- seats sold before bookings existed. Those seats are not sold to anybody, so
-- they go back into circulation rather than staying permanently unsellable.
UPDATE event_seats
   SET status = 'AVAILABLE',
       booking_id = NULL,
       held_by_reservation_id = NULL,
       held_until = NULL,
       version = version + 1,
       updated_at = now()
 WHERE booking_id IS NOT NULL;

ALTER TABLE event_seats
    ADD CONSTRAINT fk_event_seats_booking
    FOREIGN KEY (booking_id) REFERENCES bookings(id);
