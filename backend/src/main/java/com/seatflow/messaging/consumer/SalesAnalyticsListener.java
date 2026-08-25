package com.seatflow.messaging.consumer;

import com.seatflow.messaging.contract.PaymentCompleted;
import com.seatflow.messaging.contract.ReservationExpired;
import com.seatflow.messaging.contract.Topics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns the two money-and-inventory topics into numbers a dashboard can plot.
 *
 * <p>Deliberately a second, separate consumer of the same stream rather than
 * more branches inside the notification listener. That is the property worth
 * demonstrating: two unrelated concerns read the same facts, neither knows the
 * other exists, and the code that sells a ticket references neither.
 *
 * <p>Both counters are monotonic totals, which is exactly the shape that
 * duplicate delivery would corrupt - adding the same payment twice inflates
 * revenue with no way to tell afterwards. {@link ProcessedMessages} is what
 * stops that, and it is checked before the increment rather than after.
 */
@Component
public class SalesAnalyticsListener {

    private static final Logger log = LoggerFactory.getLogger(SalesAnalyticsListener.class);

    private final ObjectMapper objectMapper;
    private final ProcessedMessages processed;

    private final Counter revenueCents;
    private final Counter expiredReservations;
    private final Counter seatsReturned;

    public SalesAnalyticsListener(
            ObjectMapper objectMapper, ProcessedMessages processed, MeterRegistry registry) {

        this.objectMapper = objectMapper;
        this.processed = processed;

        this.revenueCents = Counter.builder("seatflow.revenue.cents")
                .description("Settled payment value, from the payment.completed topic")
                .baseUnit("cents")
                .register(registry);

        this.expiredReservations = Counter.builder("seatflow.reservations.expired")
                .description("Holds that ran out of time, from the reservation.expired topic")
                .register(registry);

        // Read against seatflow_reservation_requests_total this answers a real
        // question: how much of the demand is people holding seats and walking
        // away, rather than losing a race for them.
        this.seatsReturned = Counter.builder("seatflow.seats.returned")
                .description("Seats put back on sale by an expired hold")
                .register(registry);
    }

    @KafkaListener(
            topics = Topics.PAYMENT_COMPLETED,
            groupId = "${seatflow.messaging.consumer-group:seatflow}")
    public void onPaymentCompleted(String payload) {
        PaymentCompleted message = parse(payload, PaymentCompleted.class, Topics.PAYMENT_COMPLETED);
        if (message == null || !processed.firstSighting(message.messageId())) {
            return;
        }

        revenueCents.increment(message.amountCents());
        log.debug("Analytics: +{} {} from payment {}",
                message.amountCents(), message.currency(), message.paymentId());
    }

    @KafkaListener(
            topics = Topics.RESERVATION_EXPIRED,
            groupId = "${seatflow.messaging.consumer-group:seatflow}")
    public void onReservationExpired(String payload) {
        ReservationExpired message = parse(payload, ReservationExpired.class, Topics.RESERVATION_EXPIRED);
        if (message == null || !processed.firstSighting(message.messageId())) {
            return;
        }

        expiredReservations.increment();
        seatsReturned.increment(message.eventSeatIds().size());
        log.debug("Analytics: reservation {} expired, {} seat(s) back on sale",
                message.reservationId(), message.eventSeatIds().size());
    }

    /** @return the parsed message, or {@code null} if it will never be parseable. */
    private <T> T parse(String payload, Class<T> type, String topic) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (RuntimeException e) {
            log.error("Unreadable {} payload, skipping: {}", topic, e.getMessage());
            return null;
        }
    }
}
