package com.seatflow.payment.domain;

/**
 * Mirrors {@code ck_payment_status} in {@code V5__bookings_payments.sql}.
 *
 * <pre>
 *   PENDING -> PROCESSING -> SUCCESS (terminal)
 *                         -> FAILED  (terminal for this attempt; a retry
 *                                     creates a new payment)
 * </pre>
 *
 * PROCESSING and SUCCESS are the two states {@code uq_payment_inflight} treats
 * as live, so only one payment can be in either at a time for a reservation.
 */
public enum PaymentStatus {

    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED;

    public boolean isSettled() {
        return this == SUCCESS || this == FAILED;
    }
}
