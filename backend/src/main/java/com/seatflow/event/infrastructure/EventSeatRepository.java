package com.seatflow.event.infrastructure;

import com.seatflow.event.domain.EventSeat;
import com.seatflow.event.domain.EventSeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EventSeatRepository extends JpaRepository<EventSeat, UUID> {

    /**
     * The whole seat map for an event, in render order.
     * <p>
     * Seat and section are join-fetched: the map draws every seat with its row,
     * number, and section name, so lazy-loading them would issue one query per
     * seat for a payload that is already thousands of rows.
     */
    @Query("""
            select es from EventSeat es
              join fetch es.seat s
              join fetch s.section sec
             where es.event.id = :eventId
             order by sec.displayOrder asc, sec.name asc, s.rowLabel asc, s.seatNumber asc
            """)
    List<EventSeat> findSeatMap(@Param("eventId") UUID eventId);

    /**
     * Availability summary without transferring the seat map. Returns one row
     * per status present, as {@code [status, count]}.
     */
    @Query("""
            select es.status, count(es) from EventSeat es
             where es.event.id = :eventId
             group by es.status
            """)
    List<Object[]> countByStatus(@Param("eventId") UUID eventId);

    /**
     * Available-seat count and cheapest price for a whole page of events, as
     * {@code [eventId, count, minPriceCents]}.
     * <p>
     * One query for the page instead of two per row. The listing endpoint shows
     * both figures on every card, so doing this per event is the N+1 that would
     * actually hurt in production.
     */
    @Query("""
            select es.event.id, count(es), min(es.priceCents) from EventSeat es
             where es.event.id in :eventIds
               and es.status = com.seatflow.event.domain.EventSeatStatus.AVAILABLE
             group by es.event.id
            """)
    List<Object[]> summarizeAvailability(@Param("eventIds") Collection<UUID> eventIds);

    long countByEventId(UUID eventId);

    long countByEventIdAndStatus(UUID eventId, EventSeatStatus status);

    boolean existsByEventId(UUID eventId);
}
