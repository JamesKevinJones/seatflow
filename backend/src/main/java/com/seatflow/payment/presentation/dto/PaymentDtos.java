package com.seatflow.payment.presentation.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /**
     * @param paymentMethod a simulated instrument. The gateway is deterministic:
     *                      tokens beginning "decline" fail, everything else
     *                      succeeds. That makes the failure path testable without
     *                      randomness, which is what you want in a test suite.
     */
    public record PayRequest(
            @NotNull(message = "must be provided")
            UUID reservationId,

            String paymentMethod) {
    }

    public record BookingResponse(
            UUID id,
            String bookingReference,
            UUID eventId,
            String eventName,
            String venueName,
            Instant eventStartsAt,
            long totalCents,
            String currency,
            String status,
            Instant createdAt,
            List<BookedSeat> seats) {
    }

    public record BookedSeat(
            UUID eventSeatId,
            String label,
            String sectionName,
            long priceCents) {
    }
}
