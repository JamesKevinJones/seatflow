package com.seatflow.messaging.application;

import com.seatflow.messaging.contract.BookingConfirmed;
import com.seatflow.messaging.contract.DomainEvent;
import com.seatflow.messaging.contract.PaymentCompleted;
import com.seatflow.messaging.contract.ReservationExpired;
import com.seatflow.messaging.contract.Topics;
import com.seatflow.messaging.domain.OutboxMessage;
import com.seatflow.messaging.infrastructure.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * The way a module publishes a domain event: by writing a row.
 *
 * <p>Callers hand over a {@link DomainEvent} and get an ordinary database insert,
 * which joins whatever transaction they are already in. If the business
 * transaction rolls back, so does this - there is no message, because there was
 * no booking. If it commits, the message is committed with it and cannot be
 * lost. Kafka is not involved and does not need to be reachable.
 *
 * <p><b>{@code Propagation.MANDATORY} is doing real work here.</b> The guarantee
 * this class exists to provide is "atomic with the caller's transaction", and
 * that is only true if there <i>is</i> a caller's transaction. With the default
 * propagation, recording from a non-transactional method would quietly open its
 * own transaction and commit immediately - restoring the dual write the outbox
 * was built to remove, with no visible sign that it had. MANDATORY turns that
 * mistake into an exception on the first call instead of a lost event months
 * later.
 */
@Service
public class OutboxRecorder {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecorder.class);

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxRecorder(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(DomainEvent event) {
        Routing routing = routeFor(event);

        // Serialized now, not at send time. The relay then needs no knowledge of
        // these types, and the bytes that were committed are exactly the bytes
        // that get published.
        String payload = objectMapper.writeValueAsString(event);

        repository.save(OutboxMessage.pending(
                event.messageId(),
                event.getClass().getSimpleName(),
                routing.aggregateType(),
                routing.aggregateId(),
                routing.topic(),
                routing.partitionKey().toString(),
                payload));

        log.debug("Recorded {} ({}) for topic {}",
                event.getClass().getSimpleName(), event.messageId(), routing.topic());
    }

    /**
     * Where a message goes, decided in one place.
     *
     * <p>Exhaustive over the sealed {@link DomainEvent} hierarchy, so a new
     * event type is a compile error here until someone decides its topic. That
     * is deliberate: routing chosen by an annotation or a naming convention is
     * routing nobody reviews.
     */
    private static Routing routeFor(DomainEvent event) {
        return switch (event) {
            case BookingConfirmed e ->
                    new Routing(Topics.BOOKING_CONFIRMED, "booking", e.bookingId(), e.eventId());
            case PaymentCompleted e ->
                    new Routing(Topics.PAYMENT_COMPLETED, "payment", e.paymentId(), e.eventId());
            case ReservationExpired e ->
                    new Routing(Topics.RESERVATION_EXPIRED, "reservation", e.reservationId(), e.eventId());
        };
    }

    /**
     * @param aggregateId  what the message is about, for anyone reading the table
     * @param partitionKey the show, always. Everything concerning one event lands
     *                     on one partition and is therefore ordered relative to
     *                     the rest of that show's history.
     */
    private record Routing(String topic, String aggregateType, UUID aggregateId, UUID partitionKey) {
    }
}
