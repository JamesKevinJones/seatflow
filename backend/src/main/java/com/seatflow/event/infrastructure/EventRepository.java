package com.seatflow.event.infrastructure;

import com.seatflow.event.domain.Event;
import com.seatflow.event.domain.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {

    Optional<Event> findBySlug(String slug);

    boolean existsBySlug(String slug);

    /**
     * The public catalogue. Joins the venue eagerly because every list row
     * renders the venue name, and without it this is a textbook N+1.
     */
    @Query(value = """
            select e from Event e
              join fetch e.venue
             where e.status = :status
               and (:category is null or e.category = :category)
            """,
            countQuery = """
            select count(e) from Event e
             where e.status = :status
               and (:category is null or e.category = :category)
            """)
    Page<Event> findPublished(
            @Param("status") EventStatus status,
            @Param("category") String category,
            Pageable pageable);

    @Query("select e from Event e join fetch e.venue where e.id = :id")
    Optional<Event> findByIdWithVenue(@Param("id") UUID id);
}
