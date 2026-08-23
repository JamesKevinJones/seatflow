package com.seatflow.event.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies for events and seat maps.
 * <p>
 * All money is integer cents, matching the schema. No decimals cross this
 * boundary.
 */
public final class EventDtos {

    private EventDtos() {
    }

    public record CreateEventRequest(

            @NotNull(message = "must be provided")
            UUID venueId,

            @NotBlank(message = "must be provided")
            @Size(max = 200, message = "must be at most 200 characters")
            String name,

            @NotBlank(message = "must be provided")
            @Size(max = 60, message = "must be at most 60 characters")
            String category,

            @Size(max = 5000, message = "must be at most 5000 characters")
            String description,

            @Size(max = 2000, message = "must be at most 2000 characters")
            String posterUrl,

            @NotNull(message = "must be provided")
            @Future(message = "must be in the future")
            Instant startsAt,

            @NotNull(message = "must be provided")
            Instant endsAt,

            Instant salesStartAt,
            Instant salesEndAt,

            /** Seconds a hold survives without payment. Defaults to 600. */
            @Min(value = 30, message = "must be at least 30 seconds")
            Integer reservationHoldSeconds,

            /** Applied to every seat with no section-specific override. */
            @NotNull(message = "must be provided")
            @PositiveOrZero(message = "must not be negative")
            Long defaultPriceCents,

            @Valid
            List<SectionPrice> sectionPrices) {
    }

    /** Overrides the default price for one named section of the venue. */
    public record SectionPrice(

            @NotBlank(message = "must be provided")
            String sectionName,

            @NotNull(message = "must be provided")
            @PositiveOrZero(message = "must not be negative")
            Long priceCents) {
    }

    public record EventResponse(
            UUID id,
            String name,
            String slug,
            String description,
            String category,
            String posterUrl,
            Instant startsAt,
            Instant endsAt,
            Instant salesStartAt,
            Instant salesEndAt,
            String status,
            boolean onSale,
            int reservationHoldSeconds,
            VenueSummary venue,
            Availability availability) {
    }

    public record VenueSummary(
            UUID id,
            String name,
            String city,
            String country,
            String timezone) {
    }

    /** Seat counts by state. What a listing page shows without the full map. */
    public record Availability(
            long total,
            long available,
            long reserved,
            long booked) {
    }

    public record EventSummaryResponse(
            UUID id,
            String name,
            String slug,
            String category,
            String posterUrl,
            Instant startsAt,
            String status,
            String venueName,
            String venueCity,
            long availableSeats,
            long lowestPriceCents) {
    }

    /** The full seat map, grouped by section in render order. */
    public record SeatMapResponse(
            UUID eventId,
            String eventName,
            Availability availability,
            List<SeatMapSection> sections) {
    }

    public record SeatMapSection(
            UUID sectionId,
            String name,
            int displayOrder,
            List<SeatMapSeat> seats) {
    }

    /**
     * One seat on the map.
     *
     * @param id     the EventSeat id - this is what a reservation request sends,
     *               not the physical seat id.
     * @param status AVAILABLE, RESERVED, or BOOKED.
     */
    public record SeatMapSeat(
            UUID id,
            String rowLabel,
            int seatNumber,
            String label,
            String status,
            long priceCents,
            Integer x,
            Integer y) {
    }
}
