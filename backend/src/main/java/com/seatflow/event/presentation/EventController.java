package com.seatflow.event.presentation;

import com.seatflow.event.application.EventService;
import com.seatflow.event.application.SeatMapService;
import com.seatflow.event.presentation.dto.EventDtos.EventResponse;
import com.seatflow.event.presentation.dto.EventDtos.EventSummaryResponse;
import com.seatflow.event.presentation.dto.EventDtos.SeatMapResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The public catalogue: browsing events and viewing seat maps.
 * <p>
 * Readable without authentication. Only published events are visible - a draft
 * returns 404 rather than 403, since its existence is not public information.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService eventService;
    private final SeatMapService seatMapService;

    public EventController(EventService eventService, SeatMapService seatMapService) {
        this.eventService = eventService;
        this.seatMapService = seatMapService;
    }

    @GetMapping
    public Page<EventSummaryResponse> list(
            @RequestParam(required = false) String category,
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return eventService.listPublished(category, pageable);
    }

    @GetMapping("/{eventId}")
    public EventResponse findOne(@PathVariable UUID eventId) {
        return eventService.findById(eventId, false);
    }

    /**
     * The seat map. Statuses are a snapshot, not a reservation guarantee - the
     * reservation endpoint decides who actually gets a seat.
     */
    @GetMapping("/{eventId}/seats")
    public SeatMapResponse seatMap(@PathVariable UUID eventId) {
        return seatMapService.forEvent(eventId, false);
    }
}
