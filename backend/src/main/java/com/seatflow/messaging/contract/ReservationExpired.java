package com.seatflow.messaging.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A hold ran out of time and its seats went back on sale.
 *
 * <p>Recorded by the expiry sweeper, in the same transaction that marks the
 * reservation EXPIRED. Worth stating plainly: this message is <b>not</b> what
 * releases the seats, and nothing downstream may assume it is. The hold query
 * already treats a lapsed hold as claimable, so the seats were reservable the
 * instant the clock ran out - whether or not the sweeper had run, and whether or
 * not this message is ever delivered. It reports the housekeeping; it does not
 * perform it.
 */
public record ReservationExpired(
        UUID messageId,
        Instant occurredAt,
        UUID reservationId,
        UUID userId,
        UUID eventId,
        List<UUID> eventSeatIds,
        Instant heldUntil) implements DomainEvent {

    public ReservationExpired {
        eventSeatIds = List.copyOf(eventSeatIds);
    }
}
