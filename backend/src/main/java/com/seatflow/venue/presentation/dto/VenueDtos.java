package com.seatflow.venue.presentation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request and response bodies for venue management.
 */
public final class VenueDtos {

    private VenueDtos() {
    }

    /**
     * Creates a venue and its entire seat layout in one request.
     * <p>
     * Layouts are described by rows rather than individual seats: a 2,000 seat
     * hall is roughly 60 row specs instead of 2,000 seat objects, and the
     * generated seats still get grid coordinates for rendering.
     */
    public record CreateVenueRequest(

            @NotBlank(message = "must be provided")
            @Size(max = 200, message = "must be at most 200 characters")
            String name,

            @NotBlank(message = "must be provided")
            String address,

            @NotBlank(message = "must be provided")
            String city,

            @NotBlank(message = "must be provided")
            @Size(max = 60, message = "must be at most 60 characters")
            String country,

            // IANA zone. Defaults to UTC when absent.
            String timezone,

            @NotEmpty(message = "a venue needs at least one section")
            @Valid
            List<SectionSpec> sections) {
    }

    public record SectionSpec(

            @NotBlank(message = "must be provided")
            @Size(max = 100, message = "must be at most 100 characters")
            String name,

            int displayOrder,

            @NotEmpty(message = "a section needs at least one row")
            @Valid
            List<RowSpec> rows) {
    }

    /** One row of contiguous seats, numbered from 1. */
    public record RowSpec(

            @NotBlank(message = "must be provided")
            @Size(max = 8, message = "must be at most 8 characters")
            String rowLabel,

            @Min(value = 1, message = "must be at least 1")
            @Max(value = 500, message = "must be at most 500")
            int seatCount) {
    }

    public record VenueResponse(
            UUID id,
            String name,
            String address,
            String city,
            String country,
            String timezone,
            int totalSeats,
            List<SectionResponse> sections) {
    }

    public record SectionResponse(
            UUID id,
            String name,
            int displayOrder,
            int seatCount) {
    }

    /** List projection. Omits the layout, which is large and rarely wanted. */
    public record VenueSummaryResponse(
            UUID id,
            String name,
            String city,
            String country,
            int sectionCount) {
    }
}
