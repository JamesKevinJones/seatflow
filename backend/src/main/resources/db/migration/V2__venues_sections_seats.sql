-- Phase 2: physical venue inventory.
--
-- This is the permanent, event-independent layout of a building. A seat exists
-- here once, for the lifetime of the venue. What it costs and whether it is
-- available are per-event concerns and live in event_seats (V3).

CREATE TABLE venues (
    id         UUID        PRIMARY KEY,
    name       TEXT        NOT NULL,
    address    TEXT        NOT NULL,
    city       TEXT        NOT NULL,
    country    TEXT        NOT NULL,
    -- IANA zone, e.g. Asia/Kolkata. Event times are stored as instants; this is
    -- how the UI renders "19:30 local" for the place the event happens.
    timezone   TEXT        NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_venues_name_city UNIQUE (name, city),
    CONSTRAINT ck_venues_name_present CHECK (length(btrim(name)) > 0)
);


CREATE TABLE venue_sections (
    id            UUID    PRIMARY KEY,
    venue_id      UUID    NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    name          TEXT    NOT NULL,
    -- Rendering order on the seat map, low to high. Not a price tier: pricing is
    -- per event and lives on event_seats.
    display_order INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT uq_venue_section_name UNIQUE (venue_id, name),
    CONSTRAINT ck_venue_section_name_present CHECK (length(btrim(name)) > 0)
);

CREATE INDEX ix_venue_sections_venue ON venue_sections (venue_id, display_order);


CREATE TABLE seats (
    id                UUID    PRIMARY KEY,
    venue_section_id  UUID    NOT NULL REFERENCES venue_sections(id) ON DELETE CASCADE,
    row_label         TEXT    NOT NULL,
    seat_number       INTEGER NOT NULL,
    -- Grid coordinates for the seat map. Nullable so a venue can be imported
    -- before anyone lays it out visually.
    position_x        INTEGER,
    position_y        INTEGER,

    -- One physical position per section. This is what makes seat import
    -- re-runnable without creating duplicates.
    CONSTRAINT uq_seat_position UNIQUE (venue_section_id, row_label, seat_number),
    CONSTRAINT ck_seats_row_present CHECK (length(btrim(row_label)) > 0),
    CONSTRAINT ck_seats_number_positive CHECK (seat_number > 0)
);

-- Seat maps are always read a whole section at a time, in display order.
CREATE INDEX ix_seats_section ON seats (venue_section_id, row_label, seat_number);
