package com.seatflow.event.infrastructure;

import com.seatflow.event.application.SeatAllocationPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Implements {@link SeatAllocationPort} over the event_seats repository.
 * <p>
 * Deliberately thin. It carries no transaction annotations: seat allocation is
 * only ever meaningful inside the caller's transaction, and starting one here
 * would let a hold commit independently of the reservation it belongs to.
 */
@Component
public class SeatAllocationAdapter implements SeatAllocationPort {

    private final EventSeatRepository eventSeatRepository;

    public SeatAllocationAdapter(EventSeatRepository eventSeatRepository) {
        this.eventSeatRepository = eventSeatRepository;
    }

    @Override
    public int tryHold(UUID eventId, Collection<UUID> seatIds, UUID reservationId, Instant heldUntil) {
        if (seatIds.isEmpty()) {
            return 0;
        }
        return eventSeatRepository.tryHold(eventId, seatIds, reservationId, heldUntil);
    }

    @Override
    public int release(UUID reservationId) {
        return eventSeatRepository.releaseByReservation(reservationId);
    }

    @Override
    public List<UUID> findUnclaimable(UUID eventId, Collection<UUID> seatIds) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        return eventSeatRepository.findUnclaimable(eventId, seatIds);
    }

    @Override
    public List<SeatPrice> priceSnapshot(Collection<UUID> seatIds) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        return eventSeatRepository.findAllById(seatIds).stream()
                .map(seat -> new SeatPrice(seat.getId(), seat.getPriceCents()))
                .toList();
    }
}
