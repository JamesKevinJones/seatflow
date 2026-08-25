package com.seatflow.notification.application;

import com.seatflow.event.application.SeatStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes seat changes to everyone looking at that event's seat map.
 * <p>
 * <b>{@code AFTER_COMMIT} is the whole point of this class.</b> A plain
 * {@code @EventListener} would fire inside the transaction, so a hold that then
 * rolled back would still have told every watching browser the seat was gone.
 * The seat map would be wrong until someone reloaded, and the bug would only
 * show up under the contention that causes rollbacks in the first place.
 */
@Component
public class SeatUpdateBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SeatUpdateBroadcaster.class);

    /**
     * Per-event message counter, so a client can tell it missed something.
     * <p>
     * In memory, which is correct for one instance and wrong for several - a
     * second instance would start its own sequence and clients would see the
     * numbers go backwards. Multi-instance needs a shared source, alongside the
     * broker relay noted in WebSocketConfig.
     */
    private final Map<UUID, AtomicLong> sequences = new ConcurrentHashMap<>();

    private final SimpMessagingTemplate messaging;

    public SeatUpdateBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeatStatusChanged(SeatStatusChanged change) {
        if (change.eventSeatIds().isEmpty()) {
            return;
        }

        long seq = sequences
                .computeIfAbsent(change.eventId(), key -> new AtomicLong())
                .incrementAndGet();

        SeatUpdate update = new SeatUpdate(
                change.eventId(),
                change.eventSeatIds().stream().map(UUID::toString).toList(),
                change.status().name(),
                seq,
                change.at());

        messaging.convertAndSend(destinationFor(change.eventId()), update);
        log.debug("Broadcast #{} to event {}: {} seat(s) -> {}",
                seq, change.eventId(), change.eventSeatIds().size(), change.status());
    }

    private static String destinationFor(UUID eventId) {
        return "/topic/events/" + eventId + "/seats";
    }

    /**
     * A delta, not a snapshot.
     *
     * @param seq monotonic per event. A client that sees a gap has missed a
     *            message and should re-fetch the whole map over REST rather than
     *            keep applying deltas to a map it can no longer trust.
     */
    public record SeatUpdate(
            UUID eventId,
            List<String> seatIds,
            String status,
            long seq,
            Instant at) {
    }
}
