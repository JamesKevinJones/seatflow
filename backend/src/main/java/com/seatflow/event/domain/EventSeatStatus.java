package com.seatflow.event.domain;

/**
 * Saleable state of one seat for one event. Mirrors the
 * {@code ck_event_seat_status} check constraint.
 * <p>
 * The transitions are the subject of {@code docs/CONCURRENCY.md}:
 * <pre>
 *   AVAILABLE --reserve--&gt; RESERVED --confirm--&gt; BOOKED (terminal)
 *       ^                      |
 *       +---expire / cancel----+
 * </pre>
 */
public enum EventSeatStatus {

    AVAILABLE,
    RESERVED,
    BOOKED
}
