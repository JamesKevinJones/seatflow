package com.seatflow.booking.infrastructure;

import com.seatflow.booking.domain.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** Idempotency: one reservation yields at most one booking, ever. */
    Optional<Booking> findByReservationId(UUID reservationId);

    Optional<Booking> findByBookingReference(String bookingReference);

    @Query("select b from Booking b left join fetch b.seats where b.id = :id")
    Optional<Booking> findByIdWithSeats(@Param("id") UUID id);

    @Query("select distinct b from Booking b left join fetch b.seats where b.userId = :userId order by b.createdAt desc")
    List<Booking> findMineWithSeats(@Param("userId") UUID userId);
}
