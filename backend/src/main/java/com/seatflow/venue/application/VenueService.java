package com.seatflow.venue.application;

import com.seatflow.common.exception.ApiException;
import com.seatflow.common.exception.ErrorCode;
import com.seatflow.venue.domain.Venue;
import com.seatflow.venue.domain.VenueSection;
import com.seatflow.venue.infrastructure.VenueRepository;
import com.seatflow.venue.presentation.dto.VenueDtos.CreateVenueRequest;
import com.seatflow.venue.presentation.dto.VenueDtos.RowSpec;
import com.seatflow.venue.presentation.dto.VenueDtos.SectionResponse;
import com.seatflow.venue.presentation.dto.VenueDtos.SectionSpec;
import com.seatflow.venue.presentation.dto.VenueDtos.VenueResponse;
import com.seatflow.venue.presentation.dto.VenueDtos.VenueSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Venue and seat-layout management. Admin-facing.
 */
@Service
public class VenueService {

    private static final Logger log = LoggerFactory.getLogger(VenueService.class);

    private final VenueRepository venueRepository;

    public VenueService(VenueRepository venueRepository) {
        this.venueRepository = venueRepository;
    }

    /**
     * Creates a venue and generates its seats from the row specifications.
     * <p>
     * One transaction, so a venue can never be persisted with a half-built
     * layout. Duplicate seat positions within a section are rejected by
     * {@code uq_seat_position} rather than pre-checked in Java.
     */
    @Transactional
    public VenueResponse create(CreateVenueRequest request) {
        Venue venue = Venue.create(
                request.name(), request.address(), request.city(), request.country(), request.timezone());

        int rowIndex = 0;
        for (SectionSpec sectionSpec : request.sections()) {
            VenueSection section = venue.addSection(sectionSpec.name(), sectionSpec.displayOrder());
            for (RowSpec row : sectionSpec.rows()) {
                section.addRow(row.rowLabel(), row.seatCount(), rowIndex++);
            }
        }

        try {
            venueRepository.saveAndFlush(venue);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "This layout contains a duplicate section name or seat position.", e);
        }

        log.info("Created venue {} with {} seats", venue.getId(), venue.totalSeats());
        return toResponse(venue);
    }

    @Transactional(readOnly = true)
    public VenueResponse findById(UUID venueId) {
        Venue venue = venueRepository.findByIdWithLayout(venueId)
                .orElseThrow(() -> notFound(venueId));
        return toResponse(venue);
    }

    @Transactional(readOnly = true)
    public Page<VenueSummaryResponse> list(Pageable pageable) {
        return venueRepository.findAll(pageable).map(venue -> new VenueSummaryResponse(
                venue.getId(),
                venue.getName(),
                venue.getCity(),
                venue.getCountry(),
                venue.getSections().size()));
    }

    /** Loads a venue for another module, without projecting it to a DTO. */
    @Transactional(readOnly = true)
    public Venue requireVenue(UUID venueId) {
        return venueRepository.findById(venueId).orElseThrow(() -> notFound(venueId));
    }

    private ApiException notFound(UUID venueId) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No venue exists with that identifier.")
                .with("venueId", venueId.toString());
    }

    private VenueResponse toResponse(Venue venue) {
        List<SectionResponse> sections = venue.getSections().stream()
                .map(section -> new SectionResponse(
                        section.getId(),
                        section.getName(),
                        section.getDisplayOrder(),
                        section.seatCount()))
                .toList();

        return new VenueResponse(
                venue.getId(),
                venue.getName(),
                venue.getAddress(),
                venue.getCity(),
                venue.getCountry(),
                venue.getTimezone(),
                venue.totalSeats(),
                sections);
    }
}
