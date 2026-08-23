package com.seatflow.event.application;

import com.seatflow.common.exception.ApiException;
import com.seatflow.common.exception.ErrorCode;
import com.seatflow.event.domain.Event;
import com.seatflow.event.domain.EventSeat;
import com.seatflow.event.domain.EventSeatStatus;
import com.seatflow.event.domain.EventStatus;
import com.seatflow.event.infrastructure.EventRepository;
import com.seatflow.event.infrastructure.EventSeatRepository;
import com.seatflow.event.presentation.dto.EventDtos.Availability;
import com.seatflow.event.presentation.dto.EventDtos.CreateEventRequest;
import com.seatflow.event.presentation.dto.EventDtos.EventResponse;
import com.seatflow.event.presentation.dto.EventDtos.EventSummaryResponse;
import com.seatflow.event.presentation.dto.EventDtos.SectionPrice;
import com.seatflow.event.presentation.dto.EventDtos.VenueSummary;
import com.seatflow.venue.application.VenueService;
import com.seatflow.venue.domain.Seat;
import com.seatflow.venue.domain.Venue;
import com.seatflow.venue.infrastructure.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Event lifecycle and seat-map reads.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final EventSeatRepository eventSeatRepository;
    private final SeatRepository seatRepository;
    private final VenueService venueService;

    public EventService(
            EventRepository eventRepository,
            EventSeatRepository eventSeatRepository,
            SeatRepository seatRepository,
            VenueService venueService) {

        this.eventRepository = eventRepository;
        this.eventSeatRepository = eventSeatRepository;
        this.seatRepository = seatRepository;
        this.venueService = venueService;
    }

    /**
     * Creates an event and materializes one {@code event_seats} row per physical
     * seat in the venue.
     * <p>
     * Both happen in one transaction. A published event with a partially
     * generated seat map would sell some seats and silently hide others, so the
     * generation is not deferred or done asynchronously.
     */
    @Transactional
    public EventResponse create(CreateEventRequest request) {
        Venue venue = venueService.requireVenue(request.venueId());

        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The event must end after it starts.")
                    .with("startsAt", request.startsAt().toString())
                    .with("endsAt", request.endsAt().toString());
        }

        Event event = Event.create(
                venue,
                request.name(),
                request.category(),
                request.startsAt(),
                request.endsAt(),
                request.description(),
                request.posterUrl());

        event.setSlug(uniqueSlug(Event.slugify(request.name())));
        event.setSalesWindow(request.salesStartAt(), request.salesEndAt());
        if (request.reservationHoldSeconds() != null) {
            event.setReservationHold(Duration.ofSeconds(request.reservationHoldSeconds()));
        }

        eventRepository.saveAndFlush(event);

        int generated = generateSeats(event, venue, request);
        log.info("Created event {} at venue {} with {} seats", event.getId(), venue.getId(), generated);

        return toResponse(event, availabilityOf(event.getId()));
    }

    /**
     * Creates the per-event seat rows.
     * <p>
     * Idempotence is guaranteed by {@code uq_event_seat} on
     * {@code (event_id, seat_id)}, not by this check - the check just produces a
     * clearer error than a constraint violation would.
     */
    private int generateSeats(Event event, Venue venue, CreateEventRequest request) {
        if (eventSeatRepository.existsByEventId(event.getId())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Seats have already been generated for this event.");
        }

        List<Seat> seats = seatRepository.findAllByVenueId(venue.getId());
        if (seats.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "This venue has no seats, so the event would have nothing to sell.")
                    .with("venueId", venue.getId().toString());
        }

        Map<String, Long> priceOverrides = priceOverridesBySection(request.sectionPrices());
        long defaultPrice = request.defaultPriceCents();

        List<EventSeat> generated = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            String sectionName = seat.getSection().getName().toLowerCase(Locale.ROOT);
            long price = priceOverrides.getOrDefault(sectionName, defaultPrice);
            generated.add(EventSeat.generate(event, seat, price));
        }

        eventSeatRepository.saveAll(generated);
        return generated.size();
    }

    private Map<String, Long> priceOverridesBySection(List<SectionPrice> sectionPrices) {
        Map<String, Long> overrides = new HashMap<>();
        if (sectionPrices == null) {
            return overrides;
        }
        for (SectionPrice price : sectionPrices) {
            overrides.put(price.sectionName().trim().toLowerCase(Locale.ROOT), price.priceCents());
        }
        return overrides;
    }

    /** Appends a discriminator until the slug is free. */
    private String uniqueSlug(String base) {
        if (!eventRepository.existsBySlug(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + "-" + suffix;
            if (!eventRepository.existsBySlug(candidate)) {
                return candidate;
            }
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Transactional
    public EventResponse publish(UUID eventId) {
        Event event = requireEvent(eventId);

        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A cancelled event cannot be published.");
        }
        if (!eventSeatRepository.existsByEventId(eventId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "This event has no seats and cannot be published.");
        }

        event.publish();
        log.info("Published event {}", eventId);
        return toResponse(event, availabilityOf(eventId));
    }

    @Transactional
    public EventResponse cancel(UUID eventId) {
        Event event = requireEvent(eventId);
        event.cancel();
        log.info("Cancelled event {}", eventId);
        return toResponse(event, availabilityOf(eventId));
    }

    @Transactional(readOnly = true)
    public EventResponse findById(UUID eventId, boolean includeUnpublished) {
        Event event = eventRepository.findByIdWithVenue(eventId).orElseThrow(() -> notFound(eventId));

        if (!includeUnpublished && !event.getStatus().isPubliclyVisible()) {
            // Deliberately 404, not 403. A draft event's existence is not public.
            throw notFound(eventId);
        }
        return toResponse(event, availabilityOf(eventId));
    }

    @Transactional(readOnly = true)
    public Page<EventSummaryResponse> listPublished(String category, Pageable pageable) {
        String normalized = (category == null || category.isBlank())
                ? null
                : category.trim().toUpperCase(Locale.ROOT);

        Page<Event> events = eventRepository.findPublished(EventStatus.PUBLISHED, normalized, pageable);
        if (events.isEmpty()) {
            return events.map(event -> toSummary(event, 0L, 0L));
        }

        // One aggregate query for the whole page, rather than two per row.
        List<UUID> ids = events.getContent().stream().map(Event::getId).toList();
        Map<UUID, long[]> stats = new HashMap<>();
        for (Object[] row : eventSeatRepository.summarizeAvailability(ids)) {
            long min = row[2] == null ? 0L : ((Number) row[2]).longValue();
            stats.put((UUID) row[0], new long[]{((Number) row[1]).longValue(), min});
        }

        return events.map(event -> {
            long[] stat = stats.getOrDefault(event.getId(), new long[]{0L, 0L});
            return toSummary(event, stat[0], stat[1]);
        });
    }

    @Transactional(readOnly = true)
    public Availability availabilityOf(UUID eventId) {
        long available = 0;
        long reserved = 0;
        long booked = 0;

        for (Object[] row : eventSeatRepository.countByStatus(eventId)) {
            EventSeatStatus status = (EventSeatStatus) row[0];
            long count = ((Number) row[1]).longValue();
            switch (status) {
                case AVAILABLE -> available = count;
                case RESERVED -> reserved = count;
                case BOOKED -> booked = count;
            }
        }
        return new Availability(available + reserved + booked, available, reserved, booked);
    }

    /** Loads an event for another module without projecting it. */
    @Transactional(readOnly = true)
    public Event requireEvent(UUID eventId) {
        return eventRepository.findById(eventId).orElseThrow(() -> notFound(eventId));
    }

    private ApiException notFound(UUID eventId) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No event exists with that identifier.")
                .with("eventId", eventId.toString());
    }

    private EventResponse toResponse(Event event, Availability availability) {
        Venue venue = event.getVenue();
        return new EventResponse(
                event.getId(),
                event.getName(),
                event.getSlug(),
                event.getDescription(),
                event.getCategory(),
                event.getPosterUrl(),
                event.getStartsAt(),
                event.getEndsAt(),
                event.getSalesStartAt(),
                event.getSalesEndAt(),
                event.getStatus().name(),
                event.isOnSale(Instant.now()),
                event.getReservationHoldSeconds(),
                new VenueSummary(
                        venue.getId(), venue.getName(), venue.getCity(),
                        venue.getCountry(), venue.getTimezone()),
                availability);
    }

    private EventSummaryResponse toSummary(Event event, long availableSeats, long lowestPriceCents) {
        return new EventSummaryResponse(
                event.getId(),
                event.getName(),
                event.getSlug(),
                event.getCategory(),
                event.getPosterUrl(),
                event.getStartsAt(),
                event.getStatus().name(),
                event.getVenue().getName(),
                event.getVenue().getCity(),
                availableSeats,
                lowestPriceCents);
    }
}
