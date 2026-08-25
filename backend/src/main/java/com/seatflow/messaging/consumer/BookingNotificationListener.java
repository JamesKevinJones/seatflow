package com.seatflow.messaging.consumer;

import com.seatflow.messaging.contract.BookingConfirmed;
import com.seatflow.messaging.contract.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Sends the confirmation for a booking - or would, if this project integrated a
 * mail provider. It logs the message it would have sent and counts it.
 *
 * <p>The point being demonstrated is not the email. It is <b>where</b> the email
 * is sent from: after the booking transaction committed, in a component the
 * payment flow has never heard of, reached by a topic rather than a method call.
 * Nothing about paying for a seat blocks on it, a failure here cannot roll back
 * a sale, and adding a second consumer of the same fact - a loyalty service, a
 * data warehouse feed - requires no change to any code that sells tickets.
 *
 * <p>The listener runs in-process. The brief asks for event-driven architecture,
 * not microservices, and the {@code booking.confirmed} contract is what would
 * let this class move out of the monolith unchanged. Splitting it out today would
 * buy a deployment unit and cost the single transaction the whole design rests
 * on.
 *
 * <p><b>Consumer group, not broadcast.</b> Every instance joins the same group,
 * so Kafka gives each message to exactly one of them. That is the semantics a
 * confirmation needs - three instances must not send three emails - and it is
 * the opposite of what the live seat feed needs, which is why that goes over
 * Redis pub/sub instead.
 */
@Component
public class BookingNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(BookingNotificationListener.class);

    private final ObjectMapper objectMapper;
    private final ProcessedMessages processed;
    private final Counter sent;

    public BookingNotificationListener(
            ObjectMapper objectMapper, ProcessedMessages processed, MeterRegistry registry) {

        this.objectMapper = objectMapper;
        this.processed = processed;
        this.sent = Counter.builder("seatflow.notifications.sent")
                .description("Booking confirmations dispatched from the booking.confirmed topic")
                .register(registry);
    }

    @KafkaListener(
            topics = Topics.BOOKING_CONFIRMED,
            groupId = "${seatflow.messaging.consumer-group:seatflow}")
    public void onBookingConfirmed(String payload) {
        BookingConfirmed message;
        try {
            message = objectMapper.readValue(payload, BookingConfirmed.class);
        } catch (RuntimeException e) {
            // Retrying will not make it parseable. Log it and let the offset
            // advance rather than blocking the partition forever.
            log.error("Unreadable booking.confirmed payload, skipping: {}", e.getMessage());
            return;
        }

        if (!processed.firstSighting(message.messageId())) {
            log.debug("Ignoring duplicate booking.confirmed {}", message.messageId());
            return;
        }

        log.info("Confirmation for {}: booking {} - {} ({} seat(s)) for event {}, {} {}",
                message.userId(),
                message.bookingReference(),
                String.join(", ", message.seatLabels()),
                message.seatLabels().size(),
                message.eventId(),
                message.currency(),
                message.totalCents() / 100.0);

        sent.increment();
    }
}
