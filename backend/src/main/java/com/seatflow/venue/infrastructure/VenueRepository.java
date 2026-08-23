package com.seatflow.venue.infrastructure;

import com.seatflow.venue.domain.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VenueRepository extends JpaRepository<Venue, UUID> {

    boolean existsByNameAndCity(String name, String city);

    /**
     * Loads a venue with its whole layout in one query.
     * <p>
     * {@code distinct} is required: joining sections and then seats multiplies
     * the venue row by the number of seats, and without it the same venue comes
     * back once per seat.
     */
    @Query("""
            select distinct v from Venue v
              left join fetch v.sections s
              left join fetch s.seats
             where v.id = :id
            """)
    Optional<Venue> findByIdWithLayout(@Param("id") UUID id);
}
