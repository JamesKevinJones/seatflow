package com.seatflow.notification.infrastructure;

import com.seatflow.notification.application.SeatUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Hands a seat update to the browsers connected to <i>this</i> instance.
 *
 * <p>The last step of the path, and the only one that touches STOMP. Both routes
 * end here: a message that arrived over Redis pub/sub from another instance, and
 * a message this instance produced but could not publish because Redis was
 * unreachable. Keeping local delivery in one place is what makes those two paths
 * provably identical from the browser's point of view.
 */
@Component
public class SeatUpdateDelivery {

    private static final Logger log = LoggerFactory.getLogger(SeatUpdateDelivery.class);

    private final SimpMessagingTemplate messaging;

    public SeatUpdateDelivery(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public void toLocalSubscribers(SeatUpdate update) {
        messaging.convertAndSend(update.destination(), update);
        log.debug("Delivered #{} to local subscribers of event {}: {} seat(s) -> {}",
                update.seq(), update.eventId(), update.seatIds().size(), update.status());
    }
}
