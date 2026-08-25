package com.seatflow.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A delta sent to everyone watching a seat map.
 *
 * <p>This shape is on the wire twice: once between instances over Redis pub/sub,
 * and once from an instance to a browser over STOMP. Deliberately the same
 * record for both, so there is no translation step that could let the two drift
 * apart - the message a browser receives is the message an instance received.
 *
 * @param seq monotonic per event, across the whole cluster. A client that sees a
 *            gap has missed a message and re-fetches the whole map over REST
 *            rather than applying deltas to a map it can no longer trust. That
 *            rule is what lets everything downstream be best-effort: a lost
 *            broadcast costs one refetch, never a wrong seat map.
 */
public record SeatUpdate(
        UUID eventId,
        List<String> seatIds,
        String status,
        long seq,
        Instant at) {

    public SeatUpdate {
        seatIds = List.copyOf(seatIds);
    }

    /** The STOMP topic for this event's seat map. */
    public String destination() {
        return "/topic/events/" + eventId + "/seats";
    }
}
