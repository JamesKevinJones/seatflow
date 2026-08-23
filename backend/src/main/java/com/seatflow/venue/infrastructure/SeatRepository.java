package com.seatflow.venue.infrastructure;

import com.seatflow.venue.domain.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    /**
     * Every physical seat in a venue, section by section.
     * <p>
     * This is what EventSeat generation iterates: creating an event materializes
     * one {@code event_seats} row per seat returned here.
     */
    @Query("""
            select s from Seat s
              join fetch s.section sec
             where sec.venue.id = :venueId
             order by sec.displayOrder asc, sec.name asc, s.rowLabel asc, s.seatNumber asc
            """)
    List<Seat> findAllByVenueId(@Param("venueId") UUID venueId);

    long countBySectionVenueId(UUID venueId);
}
