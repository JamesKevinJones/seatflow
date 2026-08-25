package com.seatflow.event.infrastructure;

import com.seatflow.event.domain.EventSeat;
import com.seatflow.event.domain.EventSeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
     * <p>
     * A lapsed hold is counted as AVAILABLE, because that is what it is: the
     * hold query will happily give that seat to the next caller. Counting it as
     * RESERVED would mean the page says "held" about a seat anyone can take,
     * and the number would only become true once the sweeper caught up.
     */
    @Query(value = """
            SELECT CASE
                     WHEN status = 'RESERVED' AND held_until < now() THEN 'AVAILABLE'
                     ELSE status
                   END AS effective_status,
                   count(*)
              FROM event_seats
             WHERE event_id = :eventId
             GROUP BY effective_status
            """, nativeQuery = true)
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

    // ---------------------------------------------------------------------
    // Seat allocation. This is the contended write path. Read
    // docs/CONCURRENCY.md before changing any of it.
    // ---------------------------------------------------------------------

    /**
     * Claims seats for a reservation, atomically.
     * <p>
     * <b>The status predicate is load-bearing.</b> Under READ COMMITTED, a
     * concurrent UPDATE on the same row blocks until the first transaction
     * commits and then re-evaluates this WHERE clause against the new row
     * version. Because the clause requires the seat to still be claimable, the
     * loser matches zero rows. Delete the status check to "simplify" the query
     * and it silently becomes last-writer-wins - the exact bug this project
     * exists to prevent.
     * <p>
     * The second branch treats a lapsed hold as free, so correctness never
     * depends on the expiry sweeper having run.
     * <p>
     * {@code now()} is the database clock on purpose: it is the one clock every
     * competing transaction agrees on.
     * <p>
     * {@code clearAutomatically} matters because a bulk update bypasses the
     * persistence context; without it, entities already loaded in this
     * transaction would still report their stale status.
     *
     * @return how many seats were actually claimed. The caller compares this
     *         against the number requested; anything less must roll back.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_seats
               SET status = 'RESERVED',
                   held_by_reservation_id = :reservationId,
                   held_until = :heldUntil,
                   version = version + 1,
                   updated_at = now()
             WHERE event_id = :eventId
               AND id IN (:seatIds)
               AND ( status = 'AVAILABLE'
                  OR (status = 'RESERVED' AND held_until < now()) )
            """, nativeQuery = true)
    int tryHold(
            @Param("eventId") UUID eventId,
            @Param("seatIds") Collection<UUID> seatIds,
            @Param("reservationId") UUID reservationId,
            @Param("heldUntil") Instant heldUntil);

    /**
     * Returns the requested seats that are <em>not</em> claimable right now.
     * <p>
     * Used only to explain a failed hold. Must be read outside the failed
     * transaction, or it would see that transaction's own doomed writes.
     */
    @Query(value = """
            SELECT id FROM event_seats
             WHERE event_id = :eventId
               AND id IN (:seatIds)
               AND NOT ( status = 'AVAILABLE'
                      OR (status = 'RESERVED' AND held_until < now()) )
            """, nativeQuery = true)
    List<UUID> findUnclaimable(
            @Param("eventId") UUID eventId,
            @Param("seatIds") Collection<UUID> seatIds);

    /**
     * Releases every seat a reservation is holding.
     * <p>
     * Scoped to the holder, so a cancel that races an expiry sweep cannot
     * release a seat somebody else has since taken.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_seats
               SET status = 'AVAILABLE',
                   held_by_reservation_id = NULL,
                   held_until = NULL,
                   version = version + 1,
                   updated_at = now()
             WHERE held_by_reservation_id = :reservationId
               AND status = 'RESERVED'
            """, nativeQuery = true)
    int releaseByReservation(@Param("reservationId") UUID reservationId);

    /**
     * Turns a live hold into a sale.
     * <p>
     * The predicate is as load-bearing here as it is in {@link #tryHold}. It
     * requires the seat to still be RESERVED <em>by this reservation</em> and
     * the hold to still be live. A payment that takes longer than the hold
     * therefore cannot book a seat somebody else has since taken - the update
     * matches nothing and the caller sees a short count.
     *
     * @return how many seats were confirmed. Anything less than the reservation
     *         holds means the booking must not proceed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_seats
               SET status = 'BOOKED',
                   booking_id = :bookingId,
                   held_by_reservation_id = NULL,
                   held_until = NULL,
                   version = version + 1,
                   updated_at = now()
             WHERE held_by_reservation_id = :reservationId
               AND status = 'RESERVED'
               AND held_until > now()
            """, nativeQuery = true)
    int confirmForBooking(
            @Param("reservationId") UUID reservationId,
            @Param("bookingId") UUID bookingId);

    /**
     * The seats the next sweep will free, as {@code [eventId, eventSeatId]}.
     * <p>
     * Read before releasing so the broadcast can name them. Something could
     * change between this read and the update, which is acceptable: this drives
     * a live update, not a correctness decision, and a client that gets a stale
     * delta re-syncs from REST.
     */
    @Query(value = """
            SELECT event_id, id FROM event_seats
             WHERE status = 'RESERVED' AND held_until < now()
            """, nativeQuery = true)
    List<Object[]> findLapsedHolds();

    /**
     * Bulk-releases every lapsed hold. Run by the sweeper for the sake of the
     * seat map; correctness already comes from the predicate in {@link #tryHold}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_seats
               SET status = 'AVAILABLE',
                   held_by_reservation_id = NULL,
                   held_until = NULL,
                   version = version + 1,
                   updated_at = now()
             WHERE status = 'RESERVED'
               AND held_until < now()
            """, nativeQuery = true)
    int releaseExpiredHolds();

    /**
     * Pushes a live hold's expiry out, so a payment in flight is not beaten by
     * its own clock. Only extends holds that are still live - a lapsed hold
     * must not be resurrected, because the seat may already be gone.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE event_seats
               SET held_until = :newHeldUntil,
                   version = version + 1,
                   updated_at = now()
             WHERE held_by_reservation_id = :reservationId
               AND status = 'RESERVED'
               AND held_until > now()
            """, nativeQuery = true)
    int extendHold(
            @Param("reservationId") UUID reservationId,
            @Param("newHeldUntil") Instant newHeldUntil);

    /** Seats with their physical position and section, in one query. */
    @Query("select es from EventSeat es join fetch es.seat s join fetch s.section where es.id in :ids")
    List<EventSeat> findAllWithSeat(@Param("ids") Collection<UUID> ids);

    long countByEventId(UUID eventId);

    long countByEventIdAndStatus(UUID eventId, EventSeatStatus status);

    boolean existsByEventId(UUID eventId);
}
