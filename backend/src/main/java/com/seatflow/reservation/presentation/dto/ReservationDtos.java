package com.seatflow.reservation.presentation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReservationDtos {

    private ReservationDtos() {
    }

    /**
     * @param seatIds EventSeat ids, which is what the seat map hands out - not
     *                physical seat ids. Duplicates are collapsed server-side.
     */
    public record ReserveRequest(

            @NotNull(message = "must be provided")
            UUID eventId,

            @NotEmpty(message = "choose at least one seat")
            @Size(max = 8, message = "at most 8 seats in one reservation")
            List<UUID> seatIds) {
    }

    /**
     * @param expiresAt   when the hold lapses. The client counts down against
     *                    this, so it is absolute rather than a duration.
     * @param totalCents  sum of the prices quoted at the moment of the hold.
     */
    public record ReservationResponse(
            UUID id,
            UUID eventId,
            String status,
            Instant expiresAt,
            long secondsRemaining,
            long totalCents,
            List<ReservedSeat> seats) {
    }

    public record ReservedSeat(
            UUID eventSeatId,
            long priceCents) {
    }
}
