package com.seatflow.messaging.contract;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Seats were sold. This is the moment the transaction that created the booking
 * committed, so by the time anyone reads it the seats are already BOOKED.
 *
 * <p>Carries enough for a consumer to act without calling back for the booking
 * it was just told about, which is why the seat labels and the total are here
 * and not only their identifiers.
 *
 * <p>The show's <i>title</i> is deliberately absent, though. A title is mutable
 * catalogue data - an admin can rename a show the day after it sells out - and
 * freezing a display string into an immutable published fact is how a mailer
 * ends up sending the old name forever. What was sold is durable and belongs
 * here; what it is currently called is a lookup by {@code eventId} at render
 * time.
 *
 * @param eventId the show. Not this message's identity - see
 *                {@link DomainEvent#messageId()}.
 */
public record BookingConfirmed(
        UUID messageId,
        Instant occurredAt,
        UUID bookingId,
        String bookingReference,
        UUID reservationId,
        UUID paymentId,
        UUID userId,
        UUID eventId,
        List<String> seatLabels,
        long totalCents,
        String currency) implements DomainEvent {

    public BookingConfirmed {
        seatLabels = List.copyOf(seatLabels);
    }
}
