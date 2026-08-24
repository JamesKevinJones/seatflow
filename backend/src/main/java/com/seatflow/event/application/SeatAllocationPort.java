package com.seatflow.event.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The only way another module may change seat ownership.
 * <p>
 * {@code event_seats} belongs to this module, but {@code reservation} has to
 * mutate it. Rather than let another module reach into this one's repository,
 * the write path is narrowed to these operations. That boundary is what stops
 * the two modules fusing, and it means every mutation of the contended row goes
 * through one auditable place.
 * <p>
 * Booking confirmation joins this interface in Phase 7. It is deliberately not
 * declared yet: an unimplemented method is an invitation to guess at semantics
 * that have not been designed.
 */
public interface SeatAllocationPort {

    /**
     * Atomically claims seats for a reservation.
     *
     * @return the number of seats actually claimed. Fewer than requested means
     *         someone else won at least one, and the caller must roll back.
     */
    int tryHold(UUID eventId, Collection<UUID> seatIds, UUID reservationId, Instant heldUntil);

    /** Releases every seat currently held by the reservation. */
    int release(UUID reservationId);

    /** The requested seats that are not claimable right now. For error reporting. */
    List<UUID> findUnclaimable(UUID eventId, Collection<UUID> seatIds);

    /**
     * Prices of the given seats, for the snapshot written into
     * {@code reservation_seats}. What was quoted is what gets charged, even if
     * an admin reprices the seat afterwards.
     */
    List<SeatPrice> priceSnapshot(Collection<UUID> seatIds);

    /** One seat's identity and price at a point in time. */
    record SeatPrice(UUID eventSeatId, long priceCents) {
    }
}
