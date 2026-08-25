-- Phase 8: the transactional outbox.
--
-- Nothing in this system writes to Kafka inside a business transaction. Doing
-- so is a dual write, and a dual write has no correct outcome:
--
--   send to Kafka, then COMMIT  -> the commit fails and consumers act on a
--                                  booking that never happened
--   COMMIT, then send to Kafka  -> the process dies in between and the event
--                                  is lost forever, silently
--
-- There is no ordering of those two operations that is safe, because they are
-- two systems with two independent failure modes. The outbox removes the second
-- system from the transaction entirely: the event is written to *this table*, in
-- the same transaction as the booking, using the same commit. One system, one
-- atomic outcome. A separate relay then moves rows to Kafka afterwards.
--
-- The cost is that delivery becomes at-least-once rather than exactly-once - if
-- the relay dies between a successful send and marking the row published, it
-- sends again. That is the honest trade, and consumers are written to expect it.

CREATE TABLE outbox (
    -- BIGSERIAL, not UUID: the relay reads in insertion order, and a monotonic
    -- key is what makes "oldest first" meaningful.
    id            BIGSERIAL   PRIMARY KEY,

    -- Stable identity of this emission, carried in the payload and used by
    -- consumers to discard the duplicates that at-least-once delivery implies.
    -- Unique here too, so a bug that records the same message twice is caught at
    -- the source rather than at every consumer.
    --
    -- "message", not "event": in this codebase an *event* is a concert with
    -- seats, and outbox rows describe things that happened to one. Reusing the
    -- word for both would make every query ambiguous.
    message_id    UUID        NOT NULL UNIQUE,

    -- For humans reading this table. The relay does not branch on it.
    message_type  TEXT        NOT NULL,
    aggregate_type TEXT       NOT NULL,
    aggregate_id  UUID        NOT NULL,

    -- Where it goes and how it is partitioned. Resolved once, at record time,
    -- so the relay needs no knowledge of the domain at all.
    topic         TEXT        NOT NULL,
    partition_key TEXT        NOT NULL,

    -- TEXT, deliberately not JSONB. The relay is a byte pipe: it publishes
    -- exactly what was serialized, and never parses it. JSONB would normalise
    -- the value - reordering keys, dropping insignificant whitespace - so what
    -- reached Kafka would not be byte-identical to what was committed. The
    -- payload's schema is the consumers' concern, not PostgreSQL's.
    payload       TEXT        NOT NULL,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- NULL means "not yet on Kafka". This is the entire queue state; there is
    -- deliberately no high-water mark. See the index comment below.
    published_at  TIMESTAMPTZ,

    attempts      INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error    TEXT
);

-- The relay's only read path.
--
-- Partial on purpose, and it is the reason the relay tracks no cursor. A
-- "last id I processed" watermark looks obvious and is quietly wrong: id values
-- come from a sequence and are assigned at INSERT, but rows become visible at
-- COMMIT. Transaction A can take id 100 and commit after transaction B takes id
-- 101 and commits, so a relay that advanced past 101 would step over 100 and
-- lose that event permanently.
--
-- Asking "what is still unpublished" instead is immune to that: a row committed
-- late is still NULL here and gets picked up on the next poll, whatever its id.
-- The index stays small because it only ever indexes the backlog, not history.
CREATE INDEX ix_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

-- Published rows are kept briefly so a failed deployment can be investigated,
-- then swept. Without this index the retention sweep scans the whole table.
CREATE INDEX ix_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;
