package com.seatflow.event.application;

import com.seatflow.common.exception.ApiException;
import com.seatflow.common.exception.ErrorCode;
import com.seatflow.event.domain.Event;
import com.seatflow.event.domain.EventSeat;
import com.seatflow.event.domain.EventSeatStatus;
import com.seatflow.event.infrastructure.EventRepository;
import com.seatflow.event.infrastructure.EventSeatRepository;
import com.seatflow.event.presentation.dto.EventDtos.SeatMapResponse;
import com.seatflow.event.presentation.dto.EventDtos.SeatMapSeat;
import com.seatflow.event.presentation.dto.EventDtos.SeatMapSection;
import com.seatflow.common.config.CacheConfig;
import com.seatflow.venue.domain.VenueSection;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the seat map a client renders and clicks on.
 * <p>
 * Separate from {@link EventService} because this is the hot read path. It is
 * requested on every event page view, is the largest payload the API returns,
 * and from Phase 5 it is the thing that gets cached and invalidated. Keeping it
 * apart means that work does not have to be threaded through event lifecycle
 * code.
 */
@Service
public class SeatMapService {

    private final EventRepository eventRepository;
    private final EventSeatRepository eventSeatRepository;
    private final EventService eventService;

    public SeatMapService(
            EventRepository eventRepository,
            EventSeatRepository eventSeatRepository,
            EventService eventService) {

        this.eventRepository = eventRepository;
        this.eventSeatRepository = eventSeatRepository;
        this.eventService = eventService;
    }

    /**
     * The whole map, grouped by section in render order.
     * <p>
     * Statuses are a snapshot. A seat shown AVAILABLE may be gone by the time the
     * user clicks it - that is expected, and why the reservation endpoint decides
     * the outcome rather than this one. From Phase 6 a WebSocket feed keeps the
     * map fresh between loads.
     */
    /*
     * The most-requested and largest response in the API, and the one whose
     * invalidation is precisely known: any seat change publishes
     * SeatStatusChanged, and SeatMapCacheInvalidator evicts on it.
     *
     * The key includes the visibility flag because an admin sees draft events
     * that the public must not - sharing one entry between them would leak an
     * unpublished event to anybody who asked.
     */
    @Cacheable(cacheNames = CacheConfig.SEAT_MAPS, key = "#eventId + ':' + #includeUnpublished")
    @Transactional(readOnly = true)
    public SeatMapResponse forEvent(UUID eventId, boolean includeUnpublished) {
        Event event = eventRepository.findByIdWithVenue(eventId)
                .orElseThrow(() -> notFound(eventId));

        if (!includeUnpublished && !event.getStatus().isPubliclyVisible()) {
            throw notFound(eventId);
        }

        // Single query, with seat and section join-fetched.
        List<EventSeat> eventSeats = eventSeatRepository.findSeatMap(eventId);

        // One timestamp for the whole map, so two seats whose holds lapse
        // milliseconds apart do not render inconsistently within one response.
        Instant now = Instant.now();

        Map<UUID, List<SeatMapSeat>> seatsBySection = new LinkedHashMap<>();
        Map<UUID, VenueSection> sectionsById = new LinkedHashMap<>();

        for (EventSeat eventSeat : eventSeats) {
            VenueSection section = eventSeat.getSeat().getSection();
            sectionsById.putIfAbsent(section.getId(), section);
            seatsBySection
                    .computeIfAbsent(section.getId(), key -> new ArrayList<>())
                    .add(toSeat(eventSeat, now));
        }

        List<SeatMapSection> sections = sectionsById.values().stream()
                .map(section -> new SeatMapSection(
                        section.getId(),
                        section.getName(),
                        section.getDisplayOrder(),
                        seatsBySection.get(section.getId())))
                .toList();

        return new SeatMapResponse(
                event.getId(),
                event.getName(),
                eventService.availabilityOf(eventId),
                sections);
    }

    private SeatMapSeat toSeat(EventSeat eventSeat, Instant now) {
        var seat = eventSeat.getSeat();

        // A hold that has lapsed is reported as free, because the reservation
        // endpoint will treat it as free. Showing the stored RESERVED until the
        // sweeper catches up would make the map disagree with what actually
        // happens when the seat is clicked.
        String status = eventSeat.isClaimable(now)
                ? EventSeatStatus.AVAILABLE.name()
                : eventSeat.getStatus().name();

        return new SeatMapSeat(
                // The EventSeat id, not the physical seat id. This is what a
                // reservation request sends.
                eventSeat.getId(),
                seat.getRowLabel(),
                seat.getSeatNumber(),
                seat.label(),
                status,
                eventSeat.getPriceCents(),
                seat.getPositionX(),
                seat.getPositionY());
    }

    private ApiException notFound(UUID eventId) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No event exists with that identifier.")
                .with("eventId", eventId.toString());
    }
}
