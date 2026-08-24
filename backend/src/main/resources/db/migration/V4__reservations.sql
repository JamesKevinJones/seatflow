-- Phase 3: temporary holds on seats.
--
-- The concurrency argument lives in docs/CONCURRENCY.md. Read it before
-- touching this file or the query that writes event_seats.

CREATE TABLE reservations (
    id              UUID        PRIMARY KEY,
    event_id        UUID        NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id         UUID        NOT NULL REFERENCES users(id),

    -- Four states, not five. Acquisition and creation happen in one
    -- transaction, so a PENDING row would never be externally observable.
    -- See the DECISIONS entry.
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',

    expires_at      TIMESTAMPTZ NOT NULL,

    -- Client-supplied. A retried POST returns the existing reservation rather
    -- than taking a second set of seats.
    idempotency_key TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_reservation_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'CANCELLED', 'COMPLETED')),
    -- NULL keys do not collide in a unique index, so callers who omit the
    -- header are simply not deduplicated.
    CONSTRAINT uq_reservation_idem UNIQUE (user_id, idempotency_key)
);

-- The sweeper's only query: live holds whose time has run out.
CREATE INDEX ix_reservations_expiring ON reservations (expires_at)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_reservations_user ON reservations (user_id, created_at DESC);


CREATE TABLE reservation_seats (
    reservation_id      UUID   NOT NULL REFERENCES reservations(id) ON DELETE CASCADE,
    event_seat_id       UUID   NOT NULL REFERENCES event_seats(id),

    -- Price at the moment of the hold. The seat's own price can be changed by
    -- an admin afterwards; what was quoted is what gets charged.
    price_cents_at_hold BIGINT NOT NULL CHECK (price_cents_at_hold >= 0),

    PRIMARY KEY (reservation_id, event_seat_id)
);

CREATE INDEX ix_reservation_seats_seat ON reservation_seats (event_seat_id);


-- The forward reference V3 deliberately left as a bare UUID column, now that
-- the table it points at exists. See SCHEMA.md.
-- Before the constraint can be trusted, the column has to be clean.
--
-- event_seats.held_by_reservation_id has existed since V3 with no foreign key,
-- so anything could have been written into it - and in development, fixtures
-- did exactly that. At this point the reservations table has just been created
-- and is empty, so every non-null holder is by definition an orphan.
--
-- Released rather than deleted: a RESERVED seat whose holder does not exist is
-- a seat nobody is actually holding, and leaving it stuck would take it out of
-- circulation permanently.
UPDATE event_seats
   SET status = 'AVAILABLE',
       held_by_reservation_id = NULL,
       held_until = NULL,
       version = version + 1,
       updated_at = now()
 WHERE status = 'RESERVED';

-- Any stray holder left on a non-RESERVED row (a BOOKED seat, say) is equally
-- meaningless now.
UPDATE event_seats
   SET held_by_reservation_id = NULL
 WHERE held_by_reservation_id IS NOT NULL;


-- NO ACTION, deliberately not SET NULL. Nulling the holder while status stayed
-- 'RESERVED' would violate ck_event_seat_state, so the database refuses to
-- delete a reservation that still holds seats. Release them first. Reservations
-- are never deleted in normal operation anyway - they end as EXPIRED,
-- CANCELLED, or COMPLETED.
ALTER TABLE event_seats
    ADD CONSTRAINT fk_event_seats_held_by
    FOREIGN KEY (held_by_reservation_id) REFERENCES reservations(id);
