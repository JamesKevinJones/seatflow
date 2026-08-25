package com.seatflow.reservation.infrastructure;

import com.seatflow.reservation.application.ReservationHoldPort;
import com.seatflow.reservation.domain.Reservation;
import com.seatflow.reservation.domain.ReservationSeat;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Implements {@link ReservationHoldPort} over the reservation repository.
 * <p>
 * No transaction annotations: both operations are only meaningful inside the
 * caller's transaction. In particular the row lock taken by
 * {@code lockForPayment} is released when that transaction ends, so starting a
 * new one here would drop the lock immediately.
 */
@Component
public class ReservationHoldAdapter implements ReservationHoldPort {

    private final ReservationRepository reservationRepository;

    public ReservationHoldAdapter(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    @Override
    public Optional<Hold> lockForPayment(UUID reservationId) {
        return reservationRepository.lockById(reservationId).map(this::toHold);
    }

    @Override
    public void markCompleted(UUID reservationId) {
        reservationRepository.findById(reservationId).ifPresent(reservation -> {
            reservation.complete();
            reservationRepository.save(reservation);
        });
    }

    private Hold toHold(Reservation reservation) {
        var seats = reservation.getSeats().stream()
                .map(seat -> new SeatLine(seat.getEventSeatId(), seat.getPriceCentsAtHold()))
                .toList();

        return new Hold(
                reservation.getId(),
                reservation.getUserId(),
                reservation.getEventId(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                seats,
                reservation.getSeats().stream().mapToLong(ReservationSeat::getPriceCentsAtHold).sum());
    }
}
