package com.seatflow.reservation.application;

import com.seatflow.reservation.domain.ReservationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The narrow view of a hold that the payment flow needs.
 * <p>
 * Same reasoning as {@code SeatAllocationPort}: reservations belong to this
 * module, and payment has to lock one and mark it completed. Routing that
 * through an interface keeps the two modules from growing into each other, and
 * keeps every state change to a reservation inside the module that owns it.
 */
public interface ReservationHoldPort {

    /**
     * Loads a hold and takes a row lock on it for the rest of the transaction.
     * <p>
     * The lock is what serialises two simultaneous payment attempts for the same
     * reservation: the second waits, and then sees what the first did rather
     * than racing it. {@code uq_payment_inflight} is the backstop if that ever
     * fails.
     */
    Optional<Hold> lockForPayment(UUID reservationId);

    /** Marks the hold as paid for. Terminal. */
    void markCompleted(UUID reservationId);

    /**
     * A hold, as payment sees it.
     *
     * @param expiresAt when the seats go back to the pool. Payment must finish
     *                  before this, or the confirmation will match no seats.
     */
    record Hold(
            UUID reservationId,
            UUID userId,
            UUID eventId,
            ReservationStatus status,
            Instant expiresAt,
            List<SeatLine> seats,
            long totalCents) {

        public boolean isLive(Instant now) {
            return status == ReservationStatus.ACTIVE && now.isBefore(expiresAt);
        }
    }

    /** One seat and the price the customer was quoted for it. */
    record SeatLine(UUID eventSeatId, long priceCents) {
    }
}
