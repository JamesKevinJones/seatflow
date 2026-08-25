package com.seatflow.messaging.contract;

import java.time.Instant;
import java.util.UUID;

/**
 * Money was taken and the charge is settled.
 *
 * <p>Separate from {@link BookingConfirmed} even though both are recorded in the
 * same transaction, because they are different facts with different audiences: a
 * finance consumer cares about the amount and the provider reference and not at
 * all about which seats they were, and a mailer is the reverse. Collapsing them
 * into one message would force every consumer to filter a payload most of it
 * does not want.
 */
public record PaymentCompleted(
        UUID messageId,
        Instant occurredAt,
        UUID paymentId,
        UUID reservationId,
        UUID bookingId,
        UUID userId,
        UUID eventId,
        long amountCents,
        String currency,
        String providerReference) implements DomainEvent {
}
