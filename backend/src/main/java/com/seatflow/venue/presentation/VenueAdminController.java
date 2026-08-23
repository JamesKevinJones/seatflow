package com.seatflow.venue.presentation;

import com.seatflow.venue.application.VenueService;
import com.seatflow.venue.presentation.dto.VenueDtos.CreateVenueRequest;
import com.seatflow.venue.presentation.dto.VenueDtos.VenueResponse;
import com.seatflow.venue.presentation.dto.VenueDtos.VenueSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * Venue management. Every endpoint is admin-only.
 * <p>
 * {@code @PreAuthorize} at class level rather than a URL pattern in
 * SecurityConfig: the rule then travels with the code it protects, and a new
 * method added here cannot accidentally be public.
 */
@RestController
@RequestMapping("/api/v1/admin/venues")
@PreAuthorize("hasRole('ADMIN')")
public class VenueAdminController {

    private final VenueService venueService;

    public VenueAdminController(VenueService venueService) {
        this.venueService = venueService;
    }

    @PostMapping
    public ResponseEntity<VenueResponse> create(@Valid @RequestBody CreateVenueRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(venueService.create(request));
    }

    @GetMapping
    public Page<VenueSummaryResponse> list(@PageableDefault(size = 20) Pageable pageable) {
        return venueService.list(pageable);
    }

    @GetMapping("/{venueId}")
    public VenueResponse findOne(@PathVariable UUID venueId) {
        return venueService.findById(venueId);
    }
}
