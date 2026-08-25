package com.seatflow.notification.infrastructure;

import com.seatflow.notification.application.SeatUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * Receives seat updates from the cluster and hands them to this instance's
 * browsers.
 *
 * <p>Subscribed on every instance, including the one that published the message.
 * That symmetry is the design: there is exactly one code path from "a seat
 * changed" to "a browser was told", and it runs identically whether the change
 * happened here or on another node. The alternative - deliver locally, publish
 * for everyone else - has two paths that can drift, and the difference only
 * shows up in production with more than one instance running.
 */
@Component
public class SeatUpdateSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(SeatUpdateSubscriber.class);

    private final ObjectMapper objectMapper;
    private final SeatUpdateDelivery delivery;

    public SeatUpdateSubscriber(ObjectMapper objectMapper, SeatUpdateDelivery delivery) {
        this.objectMapper = objectMapper;
        this.delivery = delivery;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            SeatUpdate update = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), SeatUpdate.class);
            delivery.toLocalSubscribers(update);
        } catch (RuntimeException e) {
            // Never rethrow: this runs on the Redis listener thread, and killing
            // it would stop every future update on this instance for the sake of
            // one bad message. Clients recover through the sequence gap.
            log.error("Discarding an unreadable seat update from the fan-out channel: {}", e.getMessage());
        }
    }
}
