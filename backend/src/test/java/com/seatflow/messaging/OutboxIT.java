package com.seatflow.messaging;

import com.seatflow.TestcontainersConfiguration;
import com.seatflow.common.exception.ApiException;
import com.seatflow.event.application.EventService;
import com.seatflow.event.presentation.dto.EventDtos.CreateEventRequest;
import com.seatflow.event.presentation.dto.EventDtos.EventResponse;
import com.seatflow.messaging.application.OutboxRecorder;
import com.seatflow.messaging.consumer.ProcessedMessages;
import com.seatflow.messaging.contract.BookingConfirmed;
import com.seatflow.messaging.contract.PaymentCompleted;
import com.seatflow.messaging.contract.ReservationExpired;
import com.seatflow.messaging.contract.Topics;
import com.seatflow.payment.application.PaymentService;
import com.seatflow.payment.presentation.dto.PaymentDtos.BookingResponse;
import com.seatflow.payment.presentation.dto.PaymentDtos.PayRequest;
import com.seatflow.reservation.application.ReservationExpirySweeper;
import com.seatflow.reservation.application.ReservationService;
import com.seatflow.reservation.presentation.dto.ReservationDtos.ReservationResponse;
import com.seatflow.reservation.presentation.dto.ReservationDtos.ReserveRequest;
import com.seatflow.venue.application.VenueService;
import com.seatflow.venue.presentation.dto.VenueDtos.CreateVenueRequest;
import com.seatflow.venue.presentation.dto.VenueDtos.RowSpec;
import com.seatflow.venue.presentation.dto.VenueDtos.SectionSpec;
import com.seatflow.venue.presentation.dto.VenueDtos.VenueResponse;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The transactional outbox: domain events that cannot be lost and cannot be
 * invented.
 *
 * <p>What is actually under test is the <b>join</b> between a business
 * transaction and a message broker. Writing to Kafka inside the booking
 * transaction would be a dual write, so the message is written to a table
 * instead and relayed afterwards. These tests assert the two halves of that
 * bargain: the message is committed exactly when the booking is, and it reaches
 * Kafka afterwards without anyone having waited for it.
 *
 * <p>Not {@code @Transactional}, for the usual reason - the assertions are about
 * what is committed, and the relay is a different thread reading a different
 * connection. A test-managed transaction would hide every row from it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class OutboxIT {

    @Autowired private VenueService venueService;
    @Autowired private EventService eventService;
    @Autowired private ReservationService reservationService;
    @Autowired private PaymentService paymentService;
    @Autowired private ReservationExpirySweeper sweeper;
    @Autowired private OutboxRecorder outboxRecorder;
    @Autowired private ProcessedMessages processedMessages;
    @Autowired private ConsumerFactory<String, String> consumerFactory;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * The central claim, asserted on committed rows: paying wrote the booking
     * and both of its domain events under one commit.
     */
    @Test
    @DisplayName("Paying records booking.confirmed and payment.completed in the booking's own transaction")
    void payingRecordsBothMessages() {
        EventResponse event = publishedEvent(3);
        UUID user = createUser();
        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 2)), null);

        BookingResponse booking = paymentService.pay(user, new PayRequest(hold.id(), "card_ok"));

        // Both messages exist, and they describe the booking that actually
        // happened rather than something assembled afterwards.
        BookingConfirmed confirmed = objectMapper.readValue(
                payloadFor(Topics.BOOKING_CONFIRMED, booking.id()), BookingConfirmed.class);
        assertThat(confirmed.bookingId()).isEqualTo(booking.id());
        assertThat(confirmed.bookingReference()).isEqualTo(booking.bookingReference());
        assertThat(confirmed.reservationId()).isEqualTo(hold.id());
        assertThat(confirmed.userId()).isEqualTo(user);
        assertThat(confirmed.eventId()).isEqualTo(event.id());
        assertThat(confirmed.totalCents()).isEqualTo(booking.totalCents());
        // Carried in the message, so a consumer never has to call back for them.
        assertThat(confirmed.seatLabels()).hasSize(2);

        PaymentCompleted paid = objectMapper.readValue(
                payloadFor(Topics.PAYMENT_COMPLETED, paymentIdOf(hold.id())), PaymentCompleted.class);
        assertThat(paid.bookingId()).isEqualTo(booking.id());
        assertThat(paid.amountCents()).isEqualTo(booking.totalCents());
        assertThat(paid.providerReference()).isNotBlank();

        // Both partition on the show, so everything about one event is ordered.
        assertThat(partitionKeyFor(Topics.BOOKING_CONFIRMED, booking.id()))
                .isEqualTo(event.id().toString());
    }

    /**
     * The failure case for the same claim. A payment that never became a booking
     * must leave nothing behind for consumers to act on - a confirmation email
     * for a declined card is exactly the bug the outbox is meant to make
     * impossible.
     */
    @Test
    @DisplayName("A declined payment records no messages at all")
    void declinedPaymentRecordsNothing() {
        EventResponse event = publishedEvent(2);
        UUID user = createUser();
        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 1)), null);

        assertThatThrownBy(() -> paymentService.pay(user, new PayRequest(hold.id(), "decline_me")))
                .isInstanceOf(ApiException.class);

        assertThat(messageCountForReservation(hold.id())).isZero();
    }

    /**
     * The guard that keeps the guarantee honest.
     * <p>
     * {@code Propagation.MANDATORY} is the difference between "atomic with the
     * caller" and "usually atomic with the caller". Without it a caller outside
     * a transaction would get its own auto-committed one, which is the dual
     * write again wearing the outbox's clothes - and nothing would look wrong.
     */
    @Test
    @DisplayName("Recording a message outside a transaction is refused")
    void recordingWithoutATransactionIsRefused() {
        ReservationExpired orphan = new ReservationExpired(
                UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), List.of(UUID.randomUUID()), Instant.now());

        assertThatThrownBy(() -> outboxRecorder.record(orphan))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    /** The relay half: rows become Kafka records, and are then marked published. */
    @Test
    @DisplayName("The relay publishes to Kafka and marks the row published")
    void relayPublishesAndMarksTheRow() {
        EventResponse event = publishedEvent(2);
        UUID user = createUser();
        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 1)), null);

        BookingResponse booking = paymentService.pay(user, new PayRequest(hold.id(), "card_ok"));

        // The message is on the topic, read by a real consumer over a real broker.
        String published = await().atMost(Duration.ofSeconds(30))
                .until(() -> findOnTopic(Topics.BOOKING_CONFIRMED, booking.id().toString()),
                        payload -> payload != null);

        BookingConfirmed delivered = objectMapper.readValue(published, BookingConfirmed.class);
        assertThat(delivered.bookingReference()).isEqualTo(booking.bookingReference());

        // And the row is settled, so it will not be sent again on the next tick.
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(publishedAtFor(Topics.BOOKING_CONFIRMED, booking.id()))
                        .as("published_at is set once the broker acknowledges")
                        .isNotNull());
    }

    /**
     * Expiry is reported, not performed.
     * <p>
     * The seats were already claimable the moment the hold lapsed - that is the
     * hold query's doing, and it is why correctness never waited for this
     * message. What the message adds is that anything downstream can find out.
     */
    @Test
    @DisplayName("An expired hold records reservation.expired in the sweeper's transaction")
    void expiredHoldIsRecorded() {
        EventResponse event = publishedEvent(2);
        UUID user = createUser();
        ReservationResponse hold = reservationService.reserve(
                user, new ReserveRequest(event.id(), seatIds(event.id()).subList(0, 2)), null);

        // Push it into the past. The sweeper is otherwise disabled in tests.
        jdbcTemplate.update("UPDATE reservations SET expires_at = now() - interval '1 minute' WHERE id = ?",
                hold.id());
        jdbcTemplate.update("UPDATE event_seats SET held_until = now() - interval '1 minute' "
                + "WHERE held_by_reservation_id = ?", hold.id());

        sweeper.sweep();

        String payload = payloadFor(Topics.RESERVATION_EXPIRED, hold.id());
        ReservationExpired expired = objectMapper.readValue(payload, ReservationExpired.class);
        assertThat(expired.reservationId()).isEqualTo(hold.id());
        assertThat(expired.userId()).isEqualTo(user);
        assertThat(expired.eventSeatIds()).hasSize(2);
    }

    /**
     * The consumer-side half of at-least-once delivery.
     * <p>
     * The relay will resend after a crash between the send and the mark, so the
     * only thing standing between "delivered twice" and "counted twice" is the
     * message id. Worth an explicit test, because the failure it prevents -
     * quietly inflated revenue - produces no error anywhere.
     */
    @Test
    @DisplayName("A message id is acted on once, however many times it arrives")
    void duplicateDeliveryIsRecognised() {
        UUID messageId = UUID.randomUUID();

        assertThat(processedMessages.firstSighting(messageId)).isTrue();
        assertThat(processedMessages.firstSighting(messageId)).isFalse();
        assertThat(processedMessages.firstSighting(messageId)).isFalse();

        // A different message is unaffected by its neighbour.
        assertThat(processedMessages.firstSighting(UUID.randomUUID())).isTrue();
    }

    // ------------------------------------------------------------- Kafka

    /**
     * Reads a topic from the beginning in a throwaway group, so this never
     * competes with the application's own listeners for messages.
     */
    private String findOnTopic(String topic, String mustContain) {
        try (Consumer<String, String> consumer =
                     consumerFactory.createConsumer("outbox-it-" + UUID.randomUUID(), "")) {

            consumer.subscribe(List.of(topic));
            for (int attempt = 0; attempt < 5; attempt++) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.value() != null && record.value().contains(mustContain)) {
                        return record.value();
                    }
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------- outbox reads

    private String payloadFor(String topic, UUID aggregateId) {
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox WHERE topic = ? AND aggregate_id = ?",
                String.class, topic, aggregateId);
        assertThat(payloads).as("exactly one %s message for %s", topic, aggregateId).hasSize(1);
        return payloads.getFirst();
    }

    private String partitionKeyFor(String topic, UUID aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT partition_key FROM outbox WHERE topic = ? AND aggregate_id = ?",
                String.class, topic, aggregateId);
    }

    private Instant publishedAtFor(String topic, UUID aggregateId) {
        return jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox WHERE topic = ? AND aggregate_id = ?",
                Instant.class, topic, aggregateId);
    }

    /** Every message this reservation could possibly have produced, by any route. */
    private int messageCountForReservation(UUID reservationId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox WHERE payload LIKE ?",
                Integer.class, "%" + reservationId + "%");
        return n == null ? 0 : n;
    }

    private UUID paymentIdOf(UUID reservationId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM payments WHERE reservation_id = ? AND status = 'SUCCESS'",
                UUID.class, reservationId);
    }

    // ---------------------------------------------------------- fixtures

    private EventResponse publishedEvent(int seatCount) {
        VenueResponse venue = venueService.create(new CreateVenueRequest(
                "Outbox Hall " + System.nanoTime(), "1 Test St", "Testville", "India", "Asia/Kolkata",
                List.of(new SectionSpec("Stalls", 1, List.of(new RowSpec("A", seatCount))))));

        Instant starts = Instant.now().plus(15, ChronoUnit.DAYS);
        EventResponse event = eventService.create(new CreateEventRequest(
                venue.id(), "Outbox Test " + System.nanoTime(), "music", null, null,
                starts, starts.plus(2, ChronoUnit.HOURS), null, null, null, 200_000L, List.of()));
        return eventService.publish(event.id());
    }

    private List<UUID> seatIds(UUID eventId) {
        return jdbcTemplate.queryForList("""
                SELECT es.id FROM event_seats es
                  JOIN seats s ON s.id = es.seat_id
                 WHERE es.event_id = ? ORDER BY s.row_label, s.seat_number
                """, UUID.class, eventId);
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, full_name)
                VALUES (?, ?, 'not-a-real-hash', 'Outbox Tester')
                """, id, "outbox-" + id + "@example.com");
        return id;
    }
}
