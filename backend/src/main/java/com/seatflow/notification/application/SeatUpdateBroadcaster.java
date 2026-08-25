package com.seatflow.notification.application;

import com.seatflow.event.application.SeatStatusChanged;
import com.seatflow.notification.infrastructure.SeatUpdateFanout;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Turns a committed seat change into a message for everyone watching that map.
 *
 * <p><b>{@code AFTER_COMMIT} is the whole point of this class.</b> A plain
 * {@code @EventListener} would fire inside the transaction, so a hold that then
 * rolled back would still have told every watching browser the seat was gone.
 * The seat map would be wrong until someone reloaded, and the bug would only
 * show up under the contention that causes rollbacks in the first place.
 *
 * <p>What it does <i>not</i> do is deliver anything. The update goes to
 * {@link SeatUpdateFanout}, which publishes it to every instance in the cluster,
 * and each instance delivers to its own subscribers. This class stayed the same
 * shape when the system went multi-instance; only the last line changed.
 */
@Component
public class SeatUpdateBroadcaster {

    private final SeatUpdateSequence sequence;
    private final SeatUpdateFanout fanout;

    public SeatUpdateBroadcaster(SeatUpdateSequence sequence, SeatUpdateFanout fanout) {
        this.sequence = sequence;
        this.fanout = fanout;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeatStatusChanged(SeatStatusChanged change) {
        if (change.eventSeatIds().isEmpty()) {
            return;
        }

        // Numbered from a cluster-wide counter, not a field on this object. Two
        // instances with their own counters would send a browser 1, 1, 2, 2 and
        // it would resync on every message.
        long seq = sequence.next(change.eventId());

        fanout.publish(new SeatUpdate(
                change.eventId(),
                change.eventSeatIds().stream().map(UUID::toString).toList(),
                change.status().name(),
                seq,
                change.at()));
    }
}
