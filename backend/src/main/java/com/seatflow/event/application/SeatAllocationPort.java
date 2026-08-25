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

    /**
     * Turns a live hold into a sale.
     *
     * @return the number of seats confirmed. Fewer than the reservation holds
     *         means the hold lapsed mid-payment, and the booking must not stand.
     */
    int confirm(UUID reservationId, UUID bookingId);

    /** Extends a live hold, to keep it alive across a payment attempt. */
    int extendHold(UUID reservationId, Instant newHeldUntil);

    /** The requested seats that are not claimable right now. For error reporting. */
    List<UUID> findUnclaimable(UUID eventId, Collection<UUID> seatIds);

    /**
     * Describes seats: where they are and what they cost right now.
     * <p>
     * Used both to snapshot prices into {@code reservation_seats} - what was
     * quoted is what gets charged, even if an admin reprices later - and to put
     * human seat labels on a reservation that only stores identifiers.
     */
    List<SeatDetail> describe(Collection<UUID> seatIds);

    /** One seat: where it is, and what it costs at this moment. */
    record SeatDetail(UUID eventSeatId, String label, String sectionName, long priceCents) {
    }
}
