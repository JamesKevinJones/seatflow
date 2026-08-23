package com.seatflow.event.domain;

/**
 * Lifecycle of an event. Mirrors the {@code ck_events_status} check constraint
 * in {@code V3__events_event_seats.sql}.
 */
public enum EventStatus {

    /** Being set up. Not visible to the public, no seats sellable. */
    DRAFT,

    /** Visible and, within its sales window, bookable. */
    PUBLISHED,

    /** Called off. Never becomes bookable again. */
    CANCELLED,

    /** Has happened. Retained for booking history. */
    COMPLETED;

    /** Only a published event may be browsed by ordinary users. */
    public boolean isPubliclyVisible() {
        return this == PUBLISHED || this == COMPLETED;
    }

    public boolean isBookable() {
        return this == PUBLISHED;
    }
}
