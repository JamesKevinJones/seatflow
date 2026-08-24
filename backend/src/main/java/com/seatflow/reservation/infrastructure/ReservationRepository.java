package com.seatflow.reservation.infrastructure;

import com.seatflow.reservation.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * Idempotency lookup. A retried POST with the same key must return the
     * reservation the first attempt created, not take a second set of seats.
     */
    Optional<Reservation> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    @Query("select r from Reservation r left join fetch r.seats where r.id = :id")
    Optional<Reservation> findByIdWithSeats(@Param("id") UUID id);

    List<Reservation> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Marks lapsed holds as EXPIRED. Housekeeping for the sake of accurate
     * status; the seats themselves are freed by
     * {@code EventSeatRepository.releaseExpiredHolds}, and correctness does not
     * depend on either running.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.status = com.seatflow.reservation.domain.ReservationStatus.EXPIRED,
                   r.updatedAt = :now
             where r.status = com.seatflow.reservation.domain.ReservationStatus.ACTIVE
               and r.expiresAt < :now
            """)
    int markLapsedAsExpired(@Param("now") Instant now);

    /**
     * Advisory lock so only one instance sweeps at a time.
     * <p>
     * {@code pg_try_advisory_xact_lock} is non-blocking and releases at commit,
     * so a crashed sweeper cannot wedge the lock. Deliberately PostgreSQL rather
     * than Redis: expiry must keep working when the cache tier is down.
     */
    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryAdvisoryLock(@Param("key") long key);
}
