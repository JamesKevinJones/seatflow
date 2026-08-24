package com.seatflow.common.exception;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when the atomic hold could not claim every requested seat.
 * <p>
 * Raising this rolls the whole transaction back, so no partial hold survives:
 * the caller gets all their seats or none of them.
 * <p>
 * It carries only the request, not the answer. Working out <em>which</em> seats
 * were lost means reading committed state, and that read must happen after this
 * transaction has rolled back - both because the doomed transaction cannot see
 * past its own writes, and because a nested transaction would take a second
 * pooled connection while still holding the first. Under real contention that
 * is a pool-exhaustion deadlock, precisely when the system is busiest.
 * <p>
 * {@code ReservationExceptionHandler} does that enrichment, outside the
 * transaction boundary.
 */
public class SeatsUnavailableException extends ApiException {

    private final UUID eventId;
    private final List<UUID> requestedSeatIds;

    public SeatsUnavailableException(UUID eventId, List<UUID> requestedSeatIds) {
        super(ErrorCode.SEAT_UNAVAILABLE, "Some of those seats are no longer available.");
        this.eventId = eventId;
        this.requestedSeatIds = List.copyOf(requestedSeatIds);
    }

    public UUID getEventId() {
        return eventId;
    }

    public List<UUID> getRequestedSeatIds() {
        return requestedSeatIds;
    }

    /** Wording once the conflicting seats are known. */
    public static String detailFor(int requested, int unavailable) {
        if (unavailable == 0) {
            // Lost the seats, then someone released them again before we looked.
            // Rare, and more honest than naming seats that now look free.
            return "Those seats were taken while your request was being processed.";
        }
        if (unavailable >= requested) {
            return requested == 1
                    ? "That seat is no longer available."
                    : "None of those seats are still available.";
        }
        return "%d of %d requested seats are no longer available.".formatted(unavailable, requested);
    }
}
