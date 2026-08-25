package com.seatflow.event.application;

import com.seatflow.event.domain.EventSeatStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Domain event: some seats for an event changed state.
 * <p>
 * Published by whatever changed them - a hold, a release, a sale, an expiry
 * sweep - and consumed by the notification module, which is the only thing that
 * knows or cares that browsers are watching.
 * <p>
 * <b>Only ever delivered after commit.</b> Publishing mid-transaction would tell
 * every connected client a seat was taken by a transaction that may still roll
 * back, and the seat map would then disagree with the database until the next
 * full refresh.
 */
public record SeatStatusChanged(
        UUID eventId,
        List<UUID> eventSeatIds,
        EventSeatStatus status,
        Instant at) {

    public static SeatStatusChanged held(UUID eventId, List<UUID> seatIds) {
        return new SeatStatusChanged(eventId, List.copyOf(seatIds), EventSeatStatus.RESERVED, Instant.now());
    }

    public static SeatStatusChanged released(UUID eventId, List<UUID> seatIds) {
        return new SeatStatusChanged(eventId, List.copyOf(seatIds), EventSeatStatus.AVAILABLE, Instant.now());
    }

    public static SeatStatusChanged booked(UUID eventId, List<UUID> seatIds) {
        return new SeatStatusChanged(eventId, List.copyOf(seatIds), EventSeatStatus.BOOKED, Instant.now());
    }
}
