package com.seatflow.event.presentation;

import com.seatflow.event.application.EventService;
import com.seatflow.event.application.SeatMapService;
import com.seatflow.event.presentation.dto.EventDtos.CreateEventRequest;
import com.seatflow.event.presentation.dto.EventDtos.EventResponse;
import com.seatflow.event.presentation.dto.EventDtos.SeatMapResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Event management. Admin-only, including reads - an admin can see draft events,
 * which the public endpoints deliberately hide.
 */
@RestController
@RequestMapping("/api/v1/admin/events")
@PreAuthorize("hasRole('ADMIN')")
public class EventAdminController {

    private final EventService eventService;
    private final SeatMapService seatMapService;

    public EventAdminController(EventService eventService, SeatMapService seatMapService) {
        this.eventService = eventService;
        this.seatMapService = seatMapService;
    }

    /** Creates the event and generates its full seat map in one transaction. */
    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventService.create(request));
    }

    @GetMapping("/{eventId}")
    public EventResponse findOne(@PathVariable UUID eventId) {
        return eventService.findById(eventId, true);
    }

    @GetMapping("/{eventId}/seats")
    public SeatMapResponse seatMap(@PathVariable UUID eventId) {
        return seatMapService.forEvent(eventId, true);
    }

    /** Makes the event visible and bookable. Requires a generated seat map. */
    @PostMapping("/{eventId}/publish")
    public EventResponse publish(@PathVariable UUID eventId) {
        return eventService.publish(eventId);
    }

    @PostMapping("/{eventId}/cancel")
    public EventResponse cancel(@PathVariable UUID eventId) {
        return eventService.cancel(eventId);
    }
}
