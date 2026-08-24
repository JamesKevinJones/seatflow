package com.seatflow.reservation.domain;

/**
 * Lifecycle of a hold. Mirrors {@code ck_reservation_status} in
 * {@code V4__reservations.sql}.
 *
 * <pre>
 *              +--&gt; EXPIRED    (held_until passed, seats released)
 *   [none] --&gt; ACTIVE --&gt; CANCELLED  (user released them)
 *              +--&gt; COMPLETED  (payment succeeded, booking created)
 * </pre>
 *
 * There is no PENDING. Creating the reservation and claiming its seats happen
 * in one transaction, so a PENDING row could never be observed from outside it.
 */
public enum ReservationStatus {

    ACTIVE,
    EXPIRED,
    CANCELLED,
    COMPLETED;

    /** Terminal states never hold seats. */
    public boolean isTerminal() {
        return this != ACTIVE;
    }
}
